"""Exercise editing through the same Android controls used by touch and TalkBack."""

from collections import Counter
import json
import re
import time
import uuid
import xml.etree.ElementTree as ET

from user_scenario_contract import assert_document as verify_document


def _bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    assert match, f"Control has no bounds: {node.attrib}"
    return tuple(map(int, match.groups()))


def _components(project):
    return Counter((component.get("name"), component.get("loc"), tuple(sorted(
        (attribute.get("name"), attribute.get("val")) for attribute in component.findall("a"))))
        for component in project.findall("circuit/comp"))


def _wires(project):
    return Counter((wire.get("from"), wire.get("to")) for wire in project.findall("circuit/wire"))


def _search_backward_motion(root, viewport):
    window = _bounds(next(root.iter("node")))
    frame = _bounds(viewport)
    left, top, right, bottom = (max(frame[0], window[0]), max(frame[1], window[1]),
                                min(frame[2], window[2]), min(frame[3], window[3]))
    assert left < right and top < bottom, "The circuit search viewport has no visible interior"
    distance = min(96, (bottom - top) // 4)
    assert distance > 0, "The circuit search viewport is too short for a reveal gesture"
    x, y = (left + right) // 2, top + (bottom - top) // 4
    return (x, y, x, y + distance, 250)


def _assert_or_insertion(project, baseline):
    assert len(project.findall("circuit")) == 1, "OR insertion introduced another circuit"
    gates = [node for node in project.findall("circuit/comp") if node.get("name") == "OR Gate"]
    assert len(gates) == 1, "Insertion did not create exactly one OR gate"
    point = re.fullmatch(r"\((-?\d+),(-?\d+)\)", gates[0].get("loc", ""))
    assert point, "OR insertion exported an invalid location"
    world_x, world_y = map(int, point.groups())
    assert world_x % 10 == world_y % 10 == 0, "OR insertion ignored grid snapping"
    # New components have no preserved XML attributes. CircCodec therefore
    # writes these five literal defaults; none are omitted by the writer.
    attributes = tuple(sorted({"facing": "east", "width": "1", "size": "50", "inputs": "2", "out": "01"}.items()))
    expected = _components(baseline) + Counter([("OR Gate", gates[0].get("loc"), attributes)])
    assert _components(project) == expected, "Insertion changed existing components or created an OR with incorrect attributes"
    assert _wires(project) == _wires(baseline), "OR insertion changed existing wires"
    return world_x, world_y



class _Editing:
    def __init__(self, scenarios):
        self.s = scenarios
        self.ui = scenarios.ui

    def values(self, key, **arguments):
        # Nested messages, such as a terminal name inside a connection action,
        # must use the same language as their surrounding message.
        result = set()
        for language, template in self.s.catalog[key].items():
            args = {name: value.get(language, value.get("en")) if isinstance(value, dict) else value
                    for name, value in arguments.items()}
            result.add(template.format(**args))
        return result

    def matches(self, node, key, *, description=False, prefix=False, **arguments):
        if key.startswith("component_") and node.get("class", "").endswith("EditText"):
            return False
        values = self.values(key, **arguments)
        attribute = "content-desc" if description else "text"
        actual = node.get(attribute, "")
        return any(actual.startswith(value) if prefix else actual == value for value in values)

    def scope(self, root, key=None):
        # Modal controls own navigation even when the dump also contains the
        # background library or an earlier sheet.
        for tag in ("editor-tool-menu", "editor-inspector-sheet"):
            panel = self.ui.tagged(root, tag)
            if panel is not None:
                return panel, tag
        sheet = self.ui.tagged(root, "editor-tool-sheet")
        circuit_list = self.ui.tagged(root, "a11y-circuit-list")
        if circuit_list is not None and (key is None or key.startswith("a11y_explorer_")):
            return circuit_list, "a11y-circuit-list"
        if sheet is not None:
            return sheet, "editor-tool-sheet"
        return root, None

    @staticmethod
    def action_node(root, node):
        parents = {child: parent for parent in root.iter() for child in parent}
        candidate = node
        while candidate.get("clickable") != "true" and candidate in parents:
            candidate = parents[candidate]
        return candidate if candidate.get("clickable") == "true" else node

    def visible_in_scope(self, root, node, scope):
        # Modal markers can have local bounds while their children use screen coordinates.
        return any(candidate is node for candidate in scope.iter()) and self.ui.visible_node(
            root, node, within=scope if scope.get("scrollable") == "true" else None)

    def nodes(self, root, key, *, description=False, prefix=False, **arguments):
        scope, _ = self.scope(root, key)
        found = []
        for node in scope.iter("node"):
            if node.get("package") != self.ui.package:
                continue
            if not self.matches(node, key, description=description, prefix=prefix, **arguments):
                continue
            target = self.action_node(root, node)
            if target.get("package") == self.ui.package and self.visible_in_scope(
                    root, target, scope) and target not in found:
                found.append(target)
        return found

    def wait_node(self, key, *, description=False, **arguments):
        def locate(root):
            matches = self.nodes(root, key, description=description, **arguments)
            assert len(matches) <= 1, f"Ambiguous {key}: {len(matches)} controls"
            return matches[0] if matches else None
        return self.wait(locate, key)

    def hide_keyboard(self):
        ime = self.ui.adb("shell", "dumpsys", "input_method")
        if re.search(r"mInputShown=true|isInputViewShown=true", ime):
            self.ui.key(4)

    def wait(self, predicate, description, timeout=20):
        deadline = time.monotonic() + timeout
        last_error = None
        while time.monotonic() < deadline:
            root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
            try:
                result = predicate(root)
                if result is not None and result is not False:
                    return result
            except AssertionError as error:
                last_error = error
            time.sleep(0.2)
        raise AssertionError(f"Timed out waiting for {description}: {last_error or 'condition remained false'}")

    def swipe(self, bounds, *, horizontal=False, reverse=False):
        left, top, right, bottom = bounds
        if horizontal:
            start = (left + (right - left) * .85, (top + bottom) / 2)
            end = (left + (right - left) * .15, start[1])
        else:
            start = ((left + right) / 2, top + (bottom - top) * .85)
            end = (start[0], top + (bottom - top) * .25)
        if reverse:
            start, end = end, start
        self.ui.adb("shell", "input", "swipe", *(str(round(v)) for v in (*start, *end)), "250")

    def scroll_region(self, root, toolbar, key=None):
        if toolbar:
            keys = [("action_undo", True), ("action_redo", True), ("a11y_import_export_project", True),
                    ("a11y_project_files", True), ("a11y_project_tools", True), ("a11y_open_help", True)] if toolbar == "top" else [
                    ("action_simulate", False), ("action_select", False), ("action_move", False),
                    ("action_wire", False), ("a11y_fit_circuit", True), ("a11y_select_all", True),
                    ("a11y_copy_selection", True), ("a11y_paste_components", True)]
            anchors = [node for key, description in keys for node in self.nodes(root, key, description=description)]
            assert anchors, "The editing toolbar has no visible controls"
            region = _bounds(next(root.iter("node"))) if toolbar == "top" else _bounds(self.s.canvas(root))
            left, right = region[0], region[2]
            ys = [(_bounds(node)[1] + _bounds(node)[3]) // 2 for node in anchors]
            y = sorted(ys)[len(ys) // 2]
            return left, y - 10, right, y + 10
        scope, tag = self.scope(root, key)
        if tag == "a11y-circuit-list":
            assert self.ui.visible_node(root, scope), "The circuit list viewport is not wholly visible"
            return _bounds(scope)
        if scope is root:
            libraries = self.nodes(root, "a11y_library", description=True)
            scope = libraries[0] if len(libraries) == 1 else root
        regions = [_bounds(node) for node in scope.iter("node")
                   if node.get("scrollable") == "true" and not node.get("class", "").endswith("EditText")
                   and self.visible_in_scope(root, node, scope)]
        regions = [rect for rect in regions if rect[3] - rect[1] > 100 and rect[2] - rect[0] > 100]
        assert regions, "The active panel exposes no scrollable content"
        return max(regions, key=lambda rect: (rect[2] - rect[0]) * (rect[3] - rect[1]))

    def reveal(self, key, *, description=False, toolbar=False, **arguments):
        initial = self.ui.screen()
        if toolbar:
            routes = ("editor-project-toolbar", "editor-command-toolbar") if toolbar == "top" else ("editor-command-toolbar",)
            node = self.ui.toolbar_action(routes, matches=lambda candidate: self.matches(
                candidate, key, description=description, **arguments), root=initial)
            if node is not None:
                return node
        _, viewport_tag = self.scope(initial, key)
        # Search both directions, stopping when the visible controls no longer
        # change. This covers a toolbar retained at either horizontal extreme.
        for reverse in (False, True):
            previous = None
            for _ in range(9):
                root = initial if initial is not None else self.ui.screen()
                initial = None
                if viewport_tag:
                    assert self.ui.tagged(root, viewport_tag) is not None, f"The active {viewport_tag} viewport disappeared"
                matches = self.nodes(root, key, description=description, **arguments)
                assert len(matches) <= 1, f"Ambiguous {key}: {len(matches)} controls"
                if matches:
                    return matches[0]
                visible = tuple((node.get("text"), node.get("content-desc"), node.get("bounds"))
                                for node in root.iter("node"))
                if visible == previous:
                    break
                previous = visible
                self.swipe(self.scroll_region(root, toolbar, key), horizontal=toolbar, reverse=reverse)
        raise AssertionError(f"Could not reveal {key} with {arguments}")

    def click(self, key, *, description=False, toolbar=False, scroll=False, **arguments):
        if key in {"action_undo", "action_redo", "a11y_project_tools"}:
            toolbar = "top"
        node = self.reveal(key, description=description, toolbar=toolbar, **arguments) if toolbar or scroll else \
            self.wait_node(key, description=description, **arguments)
        self.ui.tap(node)

    def export(self, name):
        return self.s.export_xml(name)

    def ensure_library(self):
        if not self.nodes(self.ui.screen(), "a11y_library", description=True):
            self.click("a11y_open_library", description=True, toolbar=True)
        self.s.wait_key("a11y_library", description=True)

    def field(self, index=0, count=1):
        def locate(root):
            scope, _ = self.scope(root)
            fields = [node for node in scope.iter("node")
                      if node.get("class", "").endswith("EditText") and node.get("enabled") != "false"
                      and self.visible_in_scope(root, node, scope)]
            return fields if len(fields) >= count else None

        fields = self.wait(locate, f"{count} fully visible editable fields")
        assert len(fields) == count, f"Expected {count} editable fields, found {len(fields)}"
        return fields[index]

    def open_explorer(self):
        self.click("a11y_project_tools", description=True)
        self.click("a11y_explorer_title", scroll=True)
        self.wait_node("a11y_explorer_components")

    def close_explorer(self):
        self.hide_keyboard()
        self.click("action_close")
        self.s.wait_editor()

    def close_inspector(self, label, *, facing=None):
        self.hide_keyboard()

        def applied(root):
            sheet = self.ui.tagged(root, "editor-inspector-sheet")
            if sheet is None:
                return False
            # Property dialogs expose Cancel; the inspector itself does not.
            # Its background must not admit Back while that dialog still owns it.
            window = _bounds(next(root.iter("node")))

            def presented_cancel(node):
                if (node.get("package") != self.ui.package or not self.matches(node, "action_cancel")
                        or node.get("visible-to-user") == "false"):
                    return False
                left, top, right, bottom = _bounds(node)
                return min(right, window[2]) > max(left, window[0]) and min(bottom, window[3]) > max(top, window[1])

            if any(presented_cancel(node) for node in root.iter("node")):
                return False
            labels = [node for node in sheet.iter("node")
                      if node.get("class", "").endswith("EditText") and node.get("text") == label
                      and self.visible_in_scope(root, node, sheet)]
            assert len(labels) <= 1, f"Ambiguous inspector label {label}"
            if not labels:
                return False
            if facing is not None:
                rows = self.nodes(root, "inspector_edit", description=True,
                                  name=self.s.catalog["attribute_label_facing"])
                assert len(rows) <= 1, "Ambiguous inspector Facing row"
                if not rows or sum(self.matches(node, facing) for node in rows[0].iter("node")) != 1:
                    return False
            return sheet

        self.wait(applied, f"applied inspector for {label}")
        self.ui.key(4)
        self.s.wait_editor()

    def search(self, query):
        # Search and Commands are separate lazy-list items. A visible Commands
        # button does not prove that the editor above it is wholly visible.
        search = None
        previous = None
        trace = {"query": query, "status": "PREPARING", "records": [],
                 "scope": "Search header only; UI trees and command requests are sequential observations"}
        path = self.ui.output / ("editing-search-reveal-" + uuid.uuid4().hex)

        def save():
            path.with_suffix(".json").write_text(json.dumps(trace, indent=2) + "\n")

        try:
            # Search precedes the command row, components and terminal content.
            # Reveal that earlier header with short backward steps only.
            for attempt in range(18):
                root = self.ui.screen()
                snapshot = path.with_name(path.name + f"-{attempt + 1:02d}").with_suffix(".xml")
                snapshot.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
                viewport = self.ui.tagged(root, "a11y-circuit-list")
                entry = {"tree": snapshot.name, "observedMonotonicSeconds": time.monotonic(),
                         "viewport": _bounds(viewport) if viewport is not None else None}
                trace["records"].append(entry)
                assert viewport is not None, "The circuit search viewport disappeared"
                candidate = self.ui.tagged(root, "a11y-circuit-search")
                entry["search"] = _bounds(candidate) if candidate is not None else None
                if candidate is not None and self.ui.visible_node(root, candidate, within=viewport):
                    search = candidate
                    entry["reached"] = True
                    break
                if candidate is not None:
                    assert _bounds(candidate)[1] < _bounds(viewport)[1], \
                        "The search header is not above its viewport; backward reveal cannot reach it"
                visible = tuple((node.get("text"), node.get("bounds")) for node in viewport.iter("node"))
                if visible == previous:
                    break
                previous = visible
                if attempt < 17:
                    motion = _search_backward_motion(root, viewport)
                    entry["request"] = {"start": motion[:2], "end": motion[2:4], "durationMs": motion[4],
                                        "requestedMonotonicSeconds": time.monotonic(), "commandReturned": False}
                    save()
                    self.ui.adb("shell", "input", "swipe", *(str(value) for value in motion))
                    entry["request"]["commandReturned"] = True
            assert search is not None, "The circuit search editor could not be revealed"
            self.ui.input_text(search, query)
            self.hide_keyboard()
            trace["status"] = "COMPLETE"
        except BaseException as error:
            trace.update(status="FAIL", errorType=type(error).__name__, error=str(error))
            raise
        finally:
            save()

    def component_name(self):
        node = self.reveal("a11y_explorer_select", description=True, name="", prefix=True)
        names = set()
        for candidate in node.iter("node"):
            if candidate.get("package") != self.ui.package:
                continue
            description = candidate.get("content-desc", "")
            for template in self.s.catalog["a11y_explorer_select"].values():
                before, after = template.split("{name}")
                if description.startswith(before) and (not after or description.endswith(after)):
                    name = description[len(before):len(description) - len(after) if after else None]
                    if name:
                        names.add(name)
        assert len(names) == 1, f"Expected one component selection name, found {sorted(names)!r}"
        return names.pop()

    def insert(self, kind, x, y, label=None):
        self.open_explorer()
        self.click("a11y_explorer_insert", scroll=True)
        self.wait_node("a11y_explorer_insert_kind")
        # The dialog defines query, X, and Y fields in this order.
        for index, value in ((0, kind.upper()), (1, str(x)), (2, str(y))):
            self.ui.input_text(self.field(index, 3), value)
            self.hide_keyboard()
        self.click(f"component_{kind}", scroll=True)
        self.wait_node("a11y_explorer_components")
        if label is None:
            self.close_explorer()
            return
        self.search(f"({x}, {y})")
        name = self.component_name()
        self.click("a11y_explorer_edit", name=name, scroll=True)
        self.wait_node("property_label")
        self.ui.input_text(self.field(), label)
        self.hide_keyboard()
        self.click("action_apply")
        if kind == "output":
            self.click("inspector_edit", description=True, scroll=True, name=self.s.catalog["attribute_label_facing"])
            self.click("direction_west")
            self.click("action_apply")
        self.close_inspector(label, facing="direction_west" if kind == "output" else None)

    def assert_document(self, name, checkpoint="wired"):
        xml, project = self.export(name)
        verify_document(xml, self.s.contract, checkpoint)
        return project

    def create_and_properties(self):
        self.ensure_library()
        self.s.library_click("a11y_new_circuit")
        self.assert_document("editing-empty", "empty")
        for kind, x, label in (("input", 100, "E2E_IN"), ("not", 200, "E2E_NOT"), ("output", 300, "E2E_OUT")):
            self.insert(kind, x, 100, label)
        self.assert_document("editing-components", checkpoint="components")
        self.open_explorer()
        self.search("E2E_NOT")
        self.click("a11y_explorer_edit", name="E2E_NOT", scroll=True)
        self.click("inspector_edit", description=True, scroll=True, name=self.s.catalog["attribute_label_facing"])
        self.click("direction_north")
        self.click("action_apply")
        self.close_inspector("E2E_NOT", facing="direction_north")
        self.assert_document("editing-property-north", "north")
        self.click("action_undo", description=True)
        self.assert_document("editing-property-undo", checkpoint="components")
        self.click("action_redo", description=True)
        self.assert_document("editing-property-redo", "north")
        self.click("action_undo", description=True)
        self.assert_document("editing-property-restored", checkpoint="components")

    def connect(self, source, source_port, destination, destination_port):
        self.search(source)
        self.click("a11y_explorer_ports", name=source, scroll=True)
        self.click("a11y_explorer_start_connection", description=True, scroll=True, name=f"{source}, {source_port}")
        self.search(destination)  # Keep the pending connection while filtering.
        self.click("a11y_explorer_ports", name=destination, scroll=True)
        self.click("a11y_explorer_complete_connection", description=True, scroll=True,
                   name=f"{destination}, {destination_port}")

    def assert_output(self, value):
        self.wait(lambda root: re.search(rf"\bE2E_OUT: {value}(?=,|\.|$)",
                                         self.s.canvas(root).get("content-desc", "")), f"NOT output {value}")

    def wiring_and_truth_table(self):
        self.open_explorer()
        self.connect("E2E_IN", "out", "E2E_NOT", "in")
        # E2E_NOT remains expanded after completing the first connection.
        self.click("a11y_explorer_start_connection", description=True, scroll=True, name="E2E_NOT, out")
        self.search("E2E_OUT")
        self.click("a11y_explorer_ports", name="E2E_OUT", scroll=True)
        self.click("a11y_explorer_complete_connection", description=True, scroll=True, name="E2E_OUT, in")
        self.close_explorer()
        self.assert_document("editing-wired-not")
        self.assert_output(1)
        for expected in (0, 1):
            self.open_explorer()
            self.search("E2E_IN")
            self.click("a11y_explorer_activate", name="E2E_IN", scroll=True)
            self.close_explorer()
            self.assert_output(expected)
        self.open_explorer()
        self.click("a11y_explorer_wires", scroll=True)
        names = {language: template.format(index=1, x1=100, y1=100, x2=170, y2=100)
                 for language, template in self.s.catalog["a11y_explorer_wire"].items()}
        self.click("a11y_explorer_remove", name=names, scroll=True)
        self.close_explorer()
        self.assert_document("editing-wire-removed", checkpoint="wire-removed")
        self.click("action_undo", description=True)
        self.assert_document("editing-wire-undo")
        self.click("action_redo", description=True)
        self.assert_document("editing-wire-redo", checkpoint="wire-removed")
        self.click("action_undo", description=True)
        self.assert_document("editing-wire-restored")

    def selection_and_history(self):
        self.click("a11y_select_all", description=True, toolbar=True)
        self.click("a11y_copy_selection", description=True, toolbar=True)
        self.click("a11y_paste_components", description=True, toolbar=True)
        self.assert_document("editing-pasted", "pasted")
        self.click("action_undo", description=True)
        self.assert_document("editing-paste-undo")
        self.click("action_redo", description=True)
        self.assert_document("editing-paste-redo", "pasted")
        self.click("action_undo", description=True)
        self.assert_document("editing-paste-restored")
        # History restoration clears selection, so select explicitly again.
        self.click("a11y_select_all", description=True, toolbar=True)
        self.click("a11y_delete_selection", description=True, toolbar=True)
        self.assert_document("editing-deleted", "empty")
        self.click("action_undo", description=True)
        self.assert_document("editing-delete-undo")
        self.click("action_redo", description=True)
        self.assert_document("editing-delete-redo", "empty")
        self.click("action_undo", description=True)
        self.assert_document("editing-delete-restored")

    def zoom_percent(self, root=None):
        root = self.ui.screen() if root is None else root
        nodes = self.nodes(root, "a11y_fit", description=True)
        assert len(nodes) == 1, "The canvas exposes no unique Fit/zoom control"
        texts = [node.get("text", "") for node in nodes[0].iter("node")]
        values = [int(match.group(1)) for text in texts if (match := re.fullmatch(r"(\d+)%", text))]
        assert len(values) == 1, f"The zoom control has no percentage: {nodes[0].attrib}"
        return values[0]

    def viewport_and_touch(self):
        # Start below the zoom ceiling so the Increase action must change it.
        for _ in range(3):
            self.click("a11y_zoom_out", description=True)
        before = self.zoom_percent()
        self.click("a11y_zoom_in", description=True)
        self.wait(lambda root: self.zoom_percent(root) > before, "increased zoom")
        self.click("a11y_zoom_out", description=True)
        self.wait(lambda root: abs(self.zoom_percent(root) - before) <= 1, "decreased zoom")
        self.open_explorer()
        self.search("E2E_NOT")
        self.click("a11y_explorer_select", description=True, name="E2E_NOT", scroll=True)
        self.close_explorer()
        self.click("a11y_fit_selection", description=True, toolbar=True)
        self.wait(lambda root: self.zoom_percent(root) > before, "fitted selection")
        self.click("a11y_fit", description=True)
        baseline = self.assert_document("editing-viewport-unchanged")
        self.ensure_library()
        self.click("a11y_show_components", description=True)
        self.click("a11y_insert_component", description=True, scroll=True, name=self.s.catalog["component_or"])
        left, top, right, bottom = _bounds(self.s.canvas())
        x, y = round(left + (right - left) * .65), round(top + (bottom - top) * .65)
        self.ui.adb("shell", "input", "tap", str(x), str(y))
        _, touched = self.export("editing-touch-or")
        world_x, world_y = _assert_or_insertion(touched, baseline)
        self.click("action_undo", description=True)
        self.assert_document("editing-touch-undo")
        self.insert("or", world_x, world_y)
        _, accessible = self.export("editing-accessible-or")
        _assert_or_insertion(accessible, baseline)
        assert _components(accessible) == _components(touched), "Explorer and touch inserted different components"
        assert _wires(accessible) == _wires(touched), "Explorer and touch produced different wiring"
        self.click("action_undo", description=True)
        self.assert_document("editing-final")


def run_editing(scenarios):
    """Run on an installed, isolated .e2e app; every missing action is a failure."""
    assert scenarios.ui.package.endswith(".e2e"), "Editing requires the isolated E2E application"
    editing = _Editing(scenarios)
    for name, action in (("editing-create-properties", editing.create_and_properties),
                         ("editing-wires-truth-table", editing.wiring_and_truth_table),
                         ("editing-selection-history", editing.selection_and_history),
                         ("editing-viewport-touch-parity", editing.viewport_and_touch)):
        scenarios.run(name, action)
