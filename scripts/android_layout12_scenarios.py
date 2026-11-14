"""Selected issue12 layout checks through the isolated editor's public controls.

No device action occurs on import. The owning E2E runner verifies all three APKs
before calling run_layout12. This unit does not enter Android split-screen mode.
"""

import hashlib
import json
from pathlib import Path
import re
import runpy
import xml.etree.ElementTree as ET

from android_accessibility_layout import (
    bounds, inventory, parse_wm, png_size, restore, restoration_differences,
    wait_display,
)
from android_editing_scenarios import _Editing
from android_layout12_geometry import compare_viewports, measure_viewport
from android_layout12_window import assert_control_rect, parse_window_observation


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "fixtures/user-scenarios/layout12/layout12.circ"
CONTRACT = FIXTURE.with_name("contract.json")


def fixture_bytes():
    contract = json.loads(CONTRACT.read_text())
    content = FIXTURE.read_bytes()
    assert hashlib.sha256(content).hexdigest() == contract["sha256"], "The layout fixture changed without its contract"
    project = ET.fromstring(content)
    assert len(project.findall("circuit")) == 1 and len(project.findall("circuit/comp")) == 3
    assert not project.findall("circuit/wire"), "Layout landmarks must not acquire wires"
    return content


def tablet_profile(characteristics, configuration):
    """A tablet build, or an emulator whose own display the framework classifies as large.

    Google's API 35 emulator images report ``emulator`` for every hardware
    profile, including pixel_tablet. There the framework configuration of the
    unmodified physical display is the tablet evidence: a large or xlarge
    screen bucket with a smallest width of at least 600 dp.
    """
    if "tablet" in characteristics:
        return True
    if "emulator" not in characteristics:
        return False
    match = re.search(r"(?m)^config: (\S+)$", configuration)
    if match is None:
        return False
    qualifiers = match.group(1).split("-")
    widths = [int(value[2:-2]) for value in qualifiers if re.fullmatch(r"sw\d+dp", value)]
    return len(widths) == 1 and widths[0] >= 600 and bool({"large", "xlarge"} & set(qualifiers))


def unique_editor(root, package, identifier, expected=None, *, focused=False):
    """Resolve one current EditText from its stable tagged search control."""
    parents = {child: parent for parent in root.iter() for child in parent}
    tagged = [n for n in root.iter("node") if n.get("package") == package and n.get("resource-id") == identifier]
    assert len(tagged) == 1, f"The layout search needs one exact {identifier} tag"
    tag = tagged[0]
    candidates = [n for n in tag.iter("node") if n.get("class", "").endswith("EditText")]
    ancestor = tag
    while not candidates and ancestor in parents:
        ancestor = parents[ancestor]
        if ancestor.get("class", "").endswith("EditText"):
            candidates = [ancestor]
    assert len(candidates) == 1 and candidates[0].get("package") == package, "The tagged search must own one EditText"
    editor = candidates[0]
    if expected is not None:
        assert editor.get("text") == expected, f"Expected search {expected!r}, observed {editor.get('text')!r}"
    if focused:
        assert editor.get("focused") == "true", "The same search editor lost focus"
    return editor


def selected_canvas_component(root, package, component_id):
    nodes = [node for node in root.iter("node") if node.get("package") == package and
             node.get("resource-id") == "canvas-component:" + component_id]
    assert len(nodes) == 1, "The same selected component ID must remain exposed in the canvas"
    assert nodes[0].get("selected") == "true" or nodes[0].get("checked") == "true", "Rotation lost the component selection"
    return nodes[0]


class Layout12(_Editing):
    def __init__(self, scenarios, unit, fonts):
        super().__init__(scenarios)
        self.unit, self.fonts = unit, fonts
        self.records = []
        self.counter = 0
        self.selected_id = None
        self.decode_png = runpy.run_path(str(ROOT / "scripts/test-android-assets.py"))["decode_android_png"]

    def record(self, name, root=None):
        """Retain separate observations without claiming an atomic XML/WM snapshot."""
        self.counter += 1
        stem = f"layout12-{self.counter:03d}-{name}"
        entry = {"name": name, "status": "CAPTURING", "artifacts": {},
                 "observationLimit": "XML, WindowManager and PNG are sequential captures; no shared window token is asserted."}
        self.records.append(entry)

        def retain(suffix, text):
            path = self.ui.output / f"{stem}-{suffix}.txt"
            path.write_text(text)
            entry["artifacts"][suffix] = str(path)
            self.save()

        try:
            before_raw = self.ui.adb("shell", "pidof", self.ui.package)
            retain("pid-before", before_raw)
            before = before_raw.strip()
            assert re.fullmatch(r"[1-9][0-9]*", before), "One editor process must be observable"
            root = self.ui.screen() if root is None else root
            windows = self.ui.adb("shell", "dumpsys", "window", "-a")
            retain("window", windows)
            activity = self.ui.adb("shell", "dumpsys", "activity", "activities")
            retain("activity", activity)
            density_text = self.ui.adb("shell", "wm", "density")
            retain("density", density_text)
            entry["artifacts"].update(self.ui.capture(stem, root))
            after_raw = self.ui.adb("shell", "pidof", self.ui.package)
            retain("pid-after", after_raw)
            after = after_raw.strip()
            assert before == after, "The editor process changed during a layout capture"
            size = png_size(Path(entry["artifacts"]["screenshot"]).read_bytes())
            entry.update(pid=before, density=int(parse_wm(density_text, "density").effective), displayPixels=size,
                         window=parse_window_observation(windows, self.ui.package, size, activity_dump=activity), status="CAPTURED")
            self.save()
            return root, entry
        except BaseException as error:
            entry.update(status="FAILED", error=str(error))
            self.save()
            raise

    def save(self):
        (self.ui.output / "layout12-observations.json").write_text(json.dumps({
            "unit": self.unit, "fontScales": self.fonts, "records": self.records,
            "splitScreen": "NOT_RUN", "assistiveTechnology": "NOT_RUN",
        }, indent=2) + "\n")

    def check(self, root, node, entry, *, action=False):
        assert node.get("package") == self.ui.package and node.get("enabled") != "false"
        assert self.ui.visible_node(root, node), "The entire current control must fit its observed XML scroll ancestors"
        if action:
            assert node.get("clickable") == "true", "The public action must expose a clickable control"
        assert_control_rect(bounds(node.get("bounds")), entry["window"], entry["density"], minimum_dp=48)

    def scope(self, root, key=None):
        menus = [(self.ui.tagged(root, toolbar + "-menu"), toolbar + "-menu") for toolbar in
                 ("editor-project-toolbar", "editor-command-toolbar", "editor-simulation-toolbar")]
        menus = [(node, tag) for node, tag in menus if node is not None]
        assert len(menus) <= 1, "Only one presented toolbar menu may own a layout action"
        return menus[0] if menus else super().scope(root, key)

    def export(self, name):
        # This complete route prepares the foreground receiver before revealing
        # XML. Revealing first would leave overflow open across that activity.
        return self.s.export_xml(name)

    def press(self, key, *, description=False, scroll=False, toolbar=False, **arguments):
        # Reveal first, then locate again from the retained pre-action tree.
        if key in {"action_undo", "action_redo", "a11y_project_tools", "a11y_fpga_tools"}:
            toolbar = "top"
        self.reveal(key, description=description, toolbar=toolbar, **arguments) if scroll or toolbar else self.wait_node(
            key, description=description, **arguments)
        root, entry = self.record("before-" + key)
        matches = self.nodes(root, key, description=description, **arguments)
        assert len(matches) == 1, f"The current public {key} action must be unique in its owning scope"
        self.check(root, matches[0], entry, action=True)
        self.ui.tap(matches[0])

    def close_explorer(self):
        root = self.ui.screen()
        assert self.ui.tagged(root, "editor-tool-sheet") is not None
        for _ in range(6):
            if self.ui.tagged(root, "a11y-circuit-search") is not None:
                break
            # Revealing a lower row scrolls the search field out of the lazy list; scroll back to it.
            regions = [bounds(node.get("bounds")) for node in self.ui.tagged(root, "editor-tool-sheet").iter("node")
                       if node.get("scrollable") == "true" or node.get("resource-id") == "a11y-circuit-list"]
            assert regions, "The Explorer list has no scrolling region to return to its search field"
            left, top, right, bottom = max(regions, key=lambda rect: (rect[2] - rect[0]) * (rect[3] - rect[1]))
            x = (left + right) // 2
            self.ui.adb("shell", "input", "swipe", str(x), str(top + (bottom - top) // 5),
                        str(x), str(top + (bottom - top) * 4 // 5), "300")
            root = self.ui.screen()
        assert self.ui.tagged(root, "a11y-circuit-search") is not None
        if self.ui.tagged(root, "editor-tool-hide-keyboard") is not None:
            self.press("action_hide_keyboard")
        self.wait(lambda tree: self.ui.tagged(tree, "editor-tool-close"), "Explorer Close after Hide keyboard")
        self.press("action_close")
        self.wait(lambda tree: self.ui.tagged(tree, "editor-tool-sheet") is None and
                  self.ui.tagged(tree, "a11y-circuit-search") is None, "Explorer sheet and search dismissed")
        self.s.wait_editor()

    def open_explorer(self):
        self.press("a11y_project_tools", description=True, toolbar=True)
        self.press("a11y_explorer_title", scroll=True)
        root = self.wait(lambda tree: tree if self.ui.tagged(tree, "a11y-circuit-list") is not None else None,
                         "mounted Explorer list")
        # At 200% in a 568 x 320 window the list shows only its heading; the search
        # field below it is not composed until the list scrolls, as a user would.
        # Focusing a composed field brings it into view, so composition suffices.
        previous = None
        for _ in range(10):
            if self.ui.tagged(root, "a11y-circuit-search") is not None:
                return
            sheet = self.ui.tagged(root, "editor-tool-sheet")
            state = ET.tostring(root if sheet is None else sheet)
            if state != previous:
                # The sheet may still be opening; swipe only a settled list.
                previous = state
                root = self.ui.screen()
                continue
            left, top, right, bottom = bounds(self.ui.tagged(root, "a11y-circuit-list").get("bounds"))
            x = (left + right) // 2
            self.ui.adb("shell", "input", "swipe", str(x), str(top + (bottom - top) * 4 // 5),
                        str(x), str(top + (bottom - top) // 5), "300")
            previous = None
            root = self.ui.screen()
        raise AssertionError("The Explorer search field could not be scrolled into view")

    def configure(self, rotation, font, *, editor_ready=True):
        current_size = parse_wm(self.ui.adb("shell", "wm", "size"), "size")
        current_density = parse_wm(self.ui.adb("shell", "wm", "density"), "density")
        if self.unit == "small-phone":
            if current_density.effective != "160":
                self.ui.adb("shell", "wm", "density", "160")
            if current_size.effective != "320x568":
                self.ui.adb("shell", "wm", "size", "320x568")
            expected = (320, 568) if rotation == 0 else (568, 320)
            density = 160
            applied_rotation = rotation
        else:
            physical = tuple(map(int, current_size.physical.split("x")))
            density = int(current_density.physical)
            portrait = tuple(sorted(round(value * 160 / density) for value in physical))
            expected = portrait if rotation == 0 else portrait[::-1]
            applied_rotation = rotation if physical[0] < physical[1] else 1 - rotation
        settings = {"accelerometer_rotation": "0", "user_rotation": str(applied_rotation), "font_scale": str(font)}
        for key, requested in settings.items():
            if self.ui.adb("shell", "settings", "get", "system", key).strip() != requested:
                self.ui.adb("shell", "settings", "put", "system", key, requested)
        observed_display = wait_display(self.ui, *expected, font, density)
        self.records.append({"name": "applied-configuration", "logicalOrientation": "portrait" if rotation == 0 else "landscape",
                             "userRotation": applied_rotation, "expectedDisplayDp": expected, "fontScale": font,
                             "observedDisplay": observed_display,
                             "displayMode": "simulated-small-phone" if self.unit == "small-phone" else "native-tablet-profile"})
        self.save()
        if editor_ready:
            self.s.wait_editor()

    def label_editor(self, root, expected, *, focused=False):
        assert self.ui.tagged(root, "editor-tool-sheet") is None, "The Explorer must be closed before reading the Inspector label"
        scope, _ = self.scope(root)
        fields = [node for node in scope.iter("node") if node.get("package") == self.ui.package and
                  node.get("class", "").endswith("EditText") and node.get("text") == expected and self.ui.visible_node(root, node)]
        assert len(fields) <= 1, "The selected Reset property label editor must be unique"
        field = fields[0] if fields else None
        if field is not None and focused:
            assert field.get("focused") == "true", "The pending Inspector label lost keyboard focus"
        return field

    def inspector(self, *, keep_open=False):
        self.open_explorer()
        field = unique_editor(self.ui.screen(), self.ui.package, "a11y-circuit-search")
        self.ui.input_text(field, "Reset")
        self.press("action_hide_keyboard")
        self.press("a11y_explorer_select", description=True, scroll=True, name="Reset")
        def selected(root):
            labels = self.values("a11y_explorer_select", name="Reset")
            # The tagged button may carry its description itself or on its merged text child.
            nodes = [node for node in root.iter("node") if node.get("package") == self.ui.package and
                     node.get("resource-id", "").startswith("component:") and
                     any(item.get("content-desc") in labels for item in node.iter("node")) and
                     (node.get("selected") == "true" or node.get("checked") == "true")]
            assert len(nodes) <= 1, "Explorer must expose one selected Reset component identity"
            return nodes[0] if nodes else None
        selected_id = self.wait(selected, "selected Reset identity").get("resource-id").removeprefix("component:")
        assert self.selected_id is None or self.selected_id == selected_id, "Explorer switched the Reset component identity"
        self.selected_id = selected_id
        self.close_explorer()

        if self.label_editor(self.ui.screen(), "Reset") is None:
            self.press("a11y_open_properties", description=True, toolbar=True)
        self.wait(lambda tree: self.label_editor(tree, "Reset"), "the selected Reset property label editor")
        root, entry = self.record("properties-reachable")
        field = self.label_editor(root, "Reset")
        assert field is not None, "The selected Reset label editor disappeared before capture"
        self.check(root, field, entry)
        if not keep_open and self.ui.tagged(root, "editor-inspector-sheet") is not None:
            self.ui.key(4)
            self.s.wait_editor()
        return field

    def signals(self):
        root = self.ui.screen()
        opened_sheet = self.ui.tagged(root, "editor-signal-panel") is None
        if opened_sheet:
            self.press("signals_heading", description=True, toolbar="simulation")
        for index, value in enumerate(("0", "1", "0")):
            labels = self.values("a11y_toggle_input", name="Reset", value=value)
            def signal(tree):
                panel = self.ui.tagged(tree, "editor-signal-panel")
                if panel is None:
                    return None
                matches = [self.action_node(tree, n) for n in panel.iter("node") if n.get("package") == self.ui.package
                           and n.get("content-desc") in labels]
                unique = list(dict.fromkeys(matches))
                assert len(unique) <= 1, "The named Reset signal action must be unique inside its panel"
                return unique[0] if unique else None
            self.wait(signal, "Reset signal " + value)
            root, entry = self.record("signal-" + value)
            control = signal(root)
            assert control is not None, "The observed signal changed before its recorded capture"
            self.check(root, control, entry, action=True)
            # Two taps establish 0 -> 1 -> 0; the final0 is observation only.
            if index < 2:
                self.ui.tap(control)
        if opened_sheet:
            if self.ui.tagged(self.ui.screen(), "editor-signal-sheet") is not None:
                self.ui.key(4)
            else:
                # A wider window docks the panel instead of a sheet; Back there would close the document.
                self.press("signals_heading", description=True, toolbar="simulation")
                self.wait(lambda tree: self.ui.tagged(tree, "editor-signal-panel") is None, "docked Signals panel closed")
            self.s.wait_editor()

    def painted_view(self, name):
        root, entry = self.record(name)
        canvas = self.s.canvas(root)
        assert self.ui.visible_node(root, canvas), "The whole canvas must be available for the two painted landmarks"
        assert self.selected_id is not None
        selected = selected_canvas_component(root, self.ui.package, self.selected_id)
        assert self.ui.visible_node(root, selected), "The selected component must remain visible without reselecting it"
        entry["selectedComponentId"] = self.selected_id
        measured = measure_viewport(self.decode_png(Path(entry["artifacts"]["screenshot"])), bounds(canvas.get("bounds")))
        entry["paintedViewport"] = measured
        self.save()
        return measured

    def keyboard_rotation(self, font):
        self.configure(0, font)
        self.open_explorer()
        field = unique_editor(self.ui.screen(), self.ui.package, "a11y-circuit-search")
        self.ui.input_text(field, "Re")
        root, before = self.record("focused-search-portrait")
        field = unique_editor(root, self.ui.package, "a11y-circuit-search", "Re", focused=True)
        self.check(root, field, before)
        assert any(source["type"] == "ime" for source in before["window"]["occlusions"]), "A visible software IME must be observed"
        self.configure(1, font, editor_ready=False)
        root, after = self.record("focused-search-landscape")
        field = unique_editor(root, self.ui.package, "a11y-circuit-search", "Re", focused=True)
        self.check(root, field, after)
        assert after["pid"] == before["pid"], "Rotation restarted the editor process"
        assert any(source["type"] == "ime" for source in after["window"]["occlusions"]), "The software IME disappeared on rotation"
        # A native input command appends without any tap or focus restoration.
        # This does not claim touches on individual software-keyboard keys.
        self.ui.adb("shell", "input", "text", "set")
        self.wait(lambda tree: unique_editor(tree, self.ui.package, "a11y-circuit-search", "Reset", focused=True),
                  "the same focused search appended native input after rotation")
        root, final = self.record("focused-search-appended")
        self.check(root, unique_editor(root, self.ui.package, "a11y-circuit-search", "Reset", focused=True), final)
        assert final["pid"] == before["pid"]
        self.close_explorer()

    def inspector_focus_rotation(self, font):
        self.configure(0, font)
        self.ui.input_text(self.inspector(keep_open=True), "Re")
        self.wait(lambda tree: self.label_editor(tree, "Re", focused=True), "pending focused Inspector label")
        root, before = self.record("inspector-draft-portrait")
        self.check(root, self.label_editor(root, "Re", focused=True), before)
        assert any(source["type"] == "ime" for source in before["window"]["occlusions"]), "The Inspector must have a visible software IME"
        self.configure(1, font, editor_ready=False)
        self.wait(lambda tree: self.label_editor(tree, "Re", focused=True), "the same pending Inspector label after rotation")
        root, after = self.record("inspector-draft-landscape")
        self.check(root, self.label_editor(root, "Re", focused=True), after)
        assert after["pid"] == before["pid"], "Inspector rotation restarted the editor process"
        assert any(source["type"] == "ime" for source in after["window"]["occlusions"])
        self.ui.adb("shell", "input", "text", "set")
        self.wait(lambda tree: self.label_editor(tree, "Reset", focused=True), "Inspector draft appended without refocusing")
        root, final = self.record("inspector-draft-appended")
        self.check(root, self.label_editor(root, "Reset", focused=True), final)
        assert final["pid"] == before["pid"]
        self.hide_keyboard()
        if self.ui.tagged(self.ui.screen(), "editor-inspector-sheet") is not None:
            self.ui.key(4)
        self.s.wait_editor()

    def run(self):
        original = inventory(self.ui)
        self.records.append({"name": "initial-configuration", "inventory": original})
        _, initial = self.record("window-before-layout")
        assert initial["window"]["windowingMode"] == "fullscreen", "Layout units start in an observed fullscreen app window"
        if self.unit == "tablet":
            characteristics = self.ui.adb("shell", "getprop", "ro.build.characteristics").strip().split(",")
            configuration = self.ui.adb("shell", "cmd", "activity", "get-config")
            self.records.append({"name": "tablet-profile", "characteristics": characteristics,
                                 "configuration": configuration.strip().splitlines()[:1]})
            assert tablet_profile(characteristics, configuration), \
                "The tablet unit requires an actual tablet emulator/device profile"
            assert original["wm_size"]["override"] is None and original["wm_density"]["override"] is None, \
                "A display override is not an actual tablet profile"
            size = tuple(map(int, original["wm_size"]["physical"].split("x")))
            assert min(size) * 160 / int(original["wm_density"]["physical"]) >= 600
        primary_error = None
        try:
            for font in self.fonts:
                self.configure(0, font)
                self.s.incoming(self.s.fixture(fixture_bytes()))
                self.selected_id = None
                self.s.wait_editor()
                baseline, _ = self.export(f"layout12-font{font}-baseline")
                self.inspector()
                self.signals()
                self.press("a11y_fit_circuit", description=True, toolbar=True)
                fitted = self.painted_view(f"font{font}-viewport-fitted")
                for _ in range(3):
                    self.press("a11y_zoom_out", description=True, toolbar=True)
                before = self.painted_view(f"font{font}-viewport-portrait")
                assert before["scalePixelsPerWorldUnit"] < fitted["scalePixelsPerWorldUnit"], \
                    "Public Zoom Out must actually decrease the painted scale before testing a manual viewport"
                pid = self.records[-1]["pid"]
                self.configure(1, font)
                after = self.painted_view(f"font{font}-viewport-landscape")
                assert self.records[-1]["pid"] == pid, "Viewport rotation restarted the editor process"
                self.records[-1]["retention"] = compare_viewports(before, after)
                self.configure(0, font)
                returned = self.painted_view(f"font{font}-viewport-returned-portrait")
                assert self.records[-1]["pid"] == pid
                self.records[-1]["retention"] = compare_viewports(before, returned)
                self.configure(1, font)
                self.inspector()
                self.signals()
                self.inspector_focus_rotation(font)
                self.keyboard_rotation(font)
                unchanged, _ = self.export(f"layout12-font{font}-unchanged")
                assert unchanged == baseline, "Layout or restored Signals changed the complete project XML"
        except BaseException as error:
            primary_error = error
            self.records.append({"name": "scenario-failure", "errorType": type(error).__name__, "error": str(error)})
            raise
        finally:
            self.restore_configuration(original, require_success=primary_error is None)

    def restore_configuration(self, original, *, require_success):
        entry = {"name": "restoration", "commands": restore(self.ui, original), "status": "VERIFYING"}
        self.records.append(entry)
        try:
            final = inventory(self.ui)
            differences = restoration_differences(original, final)
            success = not differences and all(command["status"] == "RESTORED" for command in entry["commands"])
            entry.update(inventory=final, differences=differences, status="PASS" if success else "FAIL")
        except BaseException as error:
            success = False
            entry.update(status="FAIL", verificationErrorType=type(error).__name__, verificationError=str(error))
        finally:
            self.save()
        if require_success:
            assert success, "The layout unit did not verify restoration of every original device setting"


def run_layout12(scenarios, *, unit="small-phone", fonts=(1.0, 2.0)):
    assert scenarios.ui.package.endswith(".e2e"), "Layout checks require an isolated E2E application"
    assert unit in ("small-phone", "tablet") and fonts and all(font in (1.0, 2.0) for font in fonts)
    fixture_bytes()
    scenarios.run("layout12-" + unit, Layout12(scenarios, unit, fonts).run)
