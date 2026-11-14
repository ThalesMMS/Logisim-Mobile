"""Validate typed Inspector edits through the isolated Android application's UI."""

from copy import deepcopy
import json
import re
import xml.etree.ElementTree as ET


LABEL = "E2E_INSPECTOR"


def _canonical(project):
    return ET.canonicalize(ET.tostring(project, encoding="unicode"), strip_text=True)


def _input(project, width):
    components = project.findall("circuit/comp")
    assert len(project.findall("circuit")) == 1 and len(components) == 1, "Inspector fixture must contain one component in one circuit"
    component = components[0]
    assert component.get("name") == "Pin" and component.get("loc") == "(100,100)", "Inspector edited or moved the wrong component"
    attributes = component.findall("a")
    assert len({node.get("name") for node in attributes}) == len(attributes), "Export contains duplicate component attributes"
    values = {node.get("name"): node.get("val") for node in attributes}
    assert values.get("label") == LABEL and values.get("type") == "input", "Inspector fixture is not the labeled input pin"
    assert values.get("width") == str(width), f"Expected the exported input width to be {width}, got {values.get('width')}"
    return component


def assert_width_edit(baseline, observed):
    """Only the known input's serialized width may differ from the observed baseline."""
    _input(baseline, 1)
    _input(observed, 4)
    expected = deepcopy(baseline)
    expected.find("circuit/comp/a[@name='width']").set("val", "4")
    assert _canonical(observed) == _canonical(expected), "Width edit changed other project content"


def assert_unchanged(baseline, observed):
    _input(baseline, 1)
    _input(observed, 1)
    assert _canonical(observed) == _canonical(baseline), "Rejected edit or Undo changed the baseline project"


def _numeric_field(root, package, expected, visible):
    fields = [node for node in root.iter("node") if node.get("package") == package
              and node.get("class", "").endswith("EditText") and node.get("text") == expected
              and node.get("enabled") == "true" and visible(root, node)]
    assert len(fields) == 1, f"Expected one visible numeric Inspector editor containing {expected!r}, found {len(fields)}"
    return fields[0]


def inspector_dialog(root, package, expected, apply_labels, cancel_labels, visible):
    """Resolve the numeric dialog independently of a property sheet underneath it."""
    parents = {child: parent for parent in root.iter() for child in parent}
    field = _numeric_field(root, package, expected, visible)

    def button(scope, labels):
        candidates = []
        for node in scope.iter("node"):
            if node.get("package") != package or node.get("text") not in labels:
                continue
            current = node
            while current is not scope and current in parents:
                if current.get("class", "").endswith("Button") or current.get("clickable") == "true":
                    break
                current = parents[current]
            if current is not scope and current not in candidates:
                candidates.append(current)
        assert len(candidates) <= 1, "The numeric Inspector dialog has ambiguous actions"
        return candidates[0] if candidates else None

    scope = parents.get(field)
    while scope is not None:
        apply = button(scope, apply_labels)
        cancel = button(scope, cancel_labels)
        if apply is not None and cancel is not None:
            assert visible(root, apply) and visible(root, cancel), "The numeric Inspector dialog's actions are clipped"
            return scope, field, apply, cancel
        scope = parents.get(scope)
    raise AssertionError("The numeric Inspector editor has no dialog with Apply and Cancel")


class _Inspector:
    def __init__(self, scenarios, editing):
        self.s = scenarios
        self.ui = scenarios.ui
        self.editing = editing

    def dialog(self, root, expected):
        return inspector_dialog(root, self.ui.package, expected,
                                self.editing.values("action_apply"), self.editing.values("action_cancel"),
                                self.ui.visible_node)

    def open_width(self):
        self.editing.open_explorer()
        self.editing.search(LABEL)
        self.editing.click("a11y_explorer_edit", name=LABEL, scroll=True)
        self.editing.click("inspector_edit", description=True, scroll=True,
                           name=self.s.catalog["attribute_label_width"])
        return self.editing.wait(lambda root: self.dialog(root, "1")[1], "numeric width editor")

    def dismiss_inspector(self):
        self.editing.hide_keyboard()
        self.ui.dismiss_inspector()
        self.s.wait_editor()

    def wait_value_dialog_closed(self, value):
        self.editing.wait(lambda root: not any(
            node.get("package") == self.ui.package and node.get("class", "").endswith("EditText")
            and node.get("text") == value for node in root.iter("node")), "numeric Inspector dialog closed")

    def invalid_value_focus_and_undo(self):
        self.editing.ensure_library()
        self.s.library_click("a11y_new_circuit")
        self.editing.insert("input", 100, 100, LABEL)
        _, baseline = self.editing.export("inspector-before")
        _input(baseline, 1)

        self.ui.input_text(self.open_width(), "0")

        def focused(root):
            field = _numeric_field(root, self.ui.package, "0", self.ui.visible_node)
            assert field.get("focused") == "true", "Numeric Inspector editor lost keyboard focus"
            ime = self.ui.adb("shell", "dumpsys", "input_method")
            assert re.search(r"mInputShown=true|isInputViewShown=true", ime), "The on-screen keyboard was not shown for the numeric Inspector editor"
            return root

        root = self.editing.wait(focused, "focused numeric width editor")
        self.ui.capture("inspector-invalid-focused", root)
        self.editing.hide_keyboard()

        # The message names the attribute as the table shows it ("Data bits") and its range (W3-01).
        errors = self.editing.values("inspector_value_invalid_range", name=self.s.catalog["attribute_label_width"],
                                     raw="0", min="1", max="64")

        def rejected(root):
            scope, _, apply, _ = self.dialog(root, "0")
            assert apply.get("enabled") == "false", "Apply must be disabled for width0"
            messages = [node for node in scope.iter("node") if node.get("package") == self.ui.package
                        and node.get("text") in errors and self.ui.visible_node(root, node)]
            assert messages, "Width0 must show its specific validation message"
            return root

        root = self.editing.wait(rejected, "rejected width0 and specific validation message")
        self.ui.capture("inspector-invalid-rejected", root)
        fresh = self.ui.screen()
        rejected(fresh)
        self.ui.tap(self.dialog(fresh, "0")[3])
        self.wait_value_dialog_closed("0")
        self.dismiss_inspector()
        _, unchanged = self.editing.export("inspector-invalid-unchanged")
        assert_unchanged(baseline, unchanged)

        self.ui.input_text(self.open_width(), "4")
        self.editing.hide_keyboard()

        def accepted(root):
            scope, _, apply, _ = self.dialog(root, "4")
            assert apply.get("enabled") == "true", "Apply must be enabled for width4"
            assert not any(node.get("text") in errors for node in scope.iter("node")), "The invalid width0 message survived the valid width4 edit"
            return apply

        self.ui.tap(self.editing.wait(accepted, "valid width4 Apply action"))
        self.wait_value_dialog_closed("4")
        self.dismiss_inspector()
        _, changed = self.editing.export("inspector-valid-width4")
        assert_width_edit(baseline, changed)
        self.editing.click("action_undo", description=True)
        _, undone = self.editing.export("inspector-width-undo")
        assert_unchanged(baseline, undone)
        (self.ui.output / "inspector-observations.json").write_text(json.dumps({
            "numericFieldFocused": True, "softwareKeyboardShown": True,
            "invalidWidth": 0, "invalidApplyEnabled": False, "invalidExportUnchanged": True,
            "validExportWidth": 4, "singleUndoExportWidth": 1,
        }, indent=2) + "\n")


def run_inspector(scenarios):
    """Integrate with the Android E2E runner after its snapshot backend is ready."""
    if not scenarios.ui.package.endswith(".e2e"):
        raise ValueError("Inspector scenarios require the isolated E2E application")
    from android_editing_scenarios import _Editing
    inspector = _Inspector(scenarios, _Editing(scenarios))
    scenarios.run("inspector-invalid-value-focus-and-undo", inspector.invalid_value_focus_and_undo)
