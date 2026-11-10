"""Drive issue #44 through production controls in the isolated Android E2E app.

Requires the Android owner's Scenarios/AndroidUi and android_editing_scenarios.
Importing this module performs no ADB operation. Native execution must be requested
by the owner after the current E2E run has released its emulator.
"""

from pathlib import Path
import json
import hashlib
import re
from datetime import datetime, timezone
import time

from io_showcase_contract import (IoShowcaseOracle, load_io_contract, assert_probe_description, pointer_screen_point)

ROOT = Path(__file__).resolve().parents[1]
IO_CASE_NAMES = ("signals", "dip-last", "pointer-controls", "keyboard-tty", "dimensions")


def normalize_io_cases(values=None):
    """Validate an explicit selection before the CLI creates or contacts AndroidUi."""
    if values is None:
        return IO_CASE_NAMES
    if not isinstance(values, (list, tuple)) or not values:
        raise ValueError("Select at least one I/O case, or omit the selection for all five cases")
    unknown = [name for name in values if name not in IO_CASE_NAMES]
    if unknown:
        raise ValueError(f"Unknown I/O cases {unknown!r}; choose from {', '.join(IO_CASE_NAMES)}")
    if len(set(values)) != len(values):
        raise ValueError("Each I/O case may be selected only once")
    return tuple(values)

def hold_pointer_with_probes(ui, start, held_outputs, released_outputs, observe, capture, seconds, *, end=None):
    """A native held phase must match its probes; always release the owned pointer."""
    assert_probe_description(observe(released_outputs), released_outputs)
    ui._require_test_app()
    down_time = datetime.now(timezone.utc).isoformat()
    started = time.monotonic()
    try:
        ui.adb("shell", "input", "touchscreen", "motionevent", "DOWN", *(str(value) for value in start))
        if end is not None:
            ui.adb("shell", "input", "touchscreen", "motionevent", "MOVE", *(str(value) for value in end))
        held = assert_probe_description(observe(held_outputs), held_outputs)
        capture()
        time.sleep(max(0, seconds - (time.monotonic() - started)))
    finally:
        ui.adb("shell", "input", "touchscreen", "motionevent", "UP", *(str(value) for value in (end or start)))
    released = assert_probe_description(observe(released_outputs), released_outputs)
    return {"recordType": "native-held-probes", "downUTC": down_time,
            "upUTC": datetime.now(timezone.utc).isoformat(), "start": start, "end": end,
            "expectedHeld": held_outputs, "heldDescription": held,
            "expectedReleased": released_outputs, "releasedDescription": released}


def keyboard_buffer_value(text, label):
    """Remove only an observed Compose caption suffix, without accepting extra input."""
    caption = re.fullmatch(r"(.*)\n" + re.escape(label) + r" · \d+/\d+", text, flags=re.DOTALL)
    return caption.group(1) if caption else text


def run_io_showcase(scenarios, selected_cases=None):
    """Run real UI cases, retaining observations; visual review remains explicit."""
    selected_cases = normalize_io_cases(selected_cases)
    # This import belongs to the Android harness, not the production app.
    from android_editing_scenarios import _Editing, _bounds

    class IoShowcase(_Editing):
        def __init__(self, host):
            super().__init__(host)
            self.contract = load_io_contract()
            self.fixture_xml = (ROOT / self.contract["fixture"]).read_bytes()
            self.records = []
            self.targets = json.loads((ROOT / "examples/io-showcase.targets.json").read_text())
            self.oracle = IoShowcaseOracle(self.fixture_xml, self.targets, self.contract)

        def fresh(self):
            self.oracle = IoShowcaseOracle(self.fixture_xml, self.targets, self.contract)
            self.s.incoming(self.s.fixture(self.fixture_xml))
            self.s.wait_editor()
            self.ui.tap(self.s.simulation_action("a11y_simulation_event_step"))
            self.s.wait_editor()
            self.assert_outputs(self.contract["baselineOutputs"])
            xml, _ = self.export("io-baseline")
            self.oracle.assert_document(xml)
            return xml

        def assert_outputs(self, outputs):
            def observed(root):
                return assert_probe_description(self.s.canvas(root).get("content-desc", ""), outputs)
            text = self.wait(observed, f"I/O probes {outputs}")
            self.records.append({"recordType": "native-canvas-probes", "expected": outputs, "observedDescription": text})
            return text

        def activate(self, label):
            self.open_explorer(); self.search(label)
            self.click("a11y_explorer_activate", name=label, scroll=True)
            self.close_explorer()

        def properties(self, label):
            self.open_explorer(); self.search(label)
            self.click("a11y_explorer_edit", name=label, scroll=True)
            self.wait(lambda root: self.ui.tagged(root, "editor-inspector-sheet"), "the I/O inspector")

        def close_properties(self):
            self.hide_keyboard()
            assert self.ui.tagged(self.ui.screen(), "editor-inspector-sheet") is not None
            self.ui.key(4)
            self.s.wait_editor()

        def checkpoint(self, name, attributes=None):
            xml, _ = self.export(name)
            self.oracle.expect(attributes or {})
            self.oracle.assert_document(xml)
            self.ui.capture(name)
            return xml

        def edit_property(self, label, name, value, *, reject=False):
            self.properties(label)
            self.click("inspector_edit", description=True, scroll=True,
                       name=self.s.catalog["attribute_label_" + name.lower()])
            field = self.field()
            self.ui.input_text(field, value)
            self.hide_keyboard()
            apply = self.wait_node("action_apply")
            if reject:
                assert apply.get("enabled") == "false", "An invalid I/O dimension must not be applied"
                self.click("action_cancel")
            else:
                assert apply.get("enabled") != "false"
                self.ui.tap(apply)
                self.oracle.expect({label: {name: value}})
            self.close_properties()

        def signals(self):
            self.fresh()
            for change in self.contract["displayTransitions"]:
                self.activate(change["source"])
                self.assert_outputs(change["outputs"])
                self.ui.capture("io-display-" + change["id"])
                self.records.append({"recordType": "visual-review-required", "component": change["visual"],
                                     "expected": change["expect"]})
            self.checkpoint("io-display-transitions")

        def dip(self):
            self.fresh()
            case = self.contract["dip"]
            self.properties(case["label"])
            for change in case["switches"]:
                self.click("io_a11y_switch", description=True, scroll=True, label=case["label"],
                           number=change["number"], state=self.s.catalog["io_switch_" + change["before"]])
                self.wait_node("io_a11y_switch", description=True, label=case["label"], number=change["number"],
                               state=self.s.catalog["io_switch_" + change["after"]])
            self.close_properties()
            self.assert_outputs(case["outputs"])
            self.checkpoint("io-dip-last", {case["label"]: case["persistedAttributes"]})
            dimension = case["dimension"]
            self.edit_property(case["label"], dimension["name"], dimension["value"])
            self.checkpoint("io-dip-four", {case["label"]: {"number": "4", "positions": "2"}})
            self.edit_property(case["label"], dimension["name"], dimension["invalid"], reject=True)
            self.checkpoint("io-dip-invalid-preserved", {case["label"]: {"number": "4", "positions": "2"}})
            self.edit_property(case["label"], dimension["name"], dimension["restore"])
            self.ui.tap(self.s.simulation_action("a11y_reset_simulation"))
            self.s.wait_editor()
            self.assert_outputs(case["outputs"])
            xml = self.checkpoint("io-dip-export", {case["label"]: case["persistedAttributes"]})
            self.s.incoming(self.s.fixture(xml.encode("utf-8")))
            self.s.wait_editor()
            self.assert_outputs(case["outputs"])
            self.checkpoint("io-dip-reopened", {case["label"]: case["persistedAttributes"]})

        def canvas_pointer(self, label, point_name):
            self.open_explorer(); self.search(label)
            self.click("a11y_explorer_select", description=True, name=label, scroll=True)
            self.close_explorer()
            self.click("action_simulate", toolbar=True)
            self.click("a11y_fit_selection", description=True, toolbar=True)
            self.s.wait_editor()

            def component(root):
                candidates = []
                for node in root.iter("node"):
                    identifier = node.get("resource-id", "").split(":id/")[-1]
                    if not identifier.startswith("canvas-component:"):
                        continue
                    descriptions = [entry.get("content-desc", "") for entry in node.iter("node")]
                    if any(value == label or value.startswith(label + ", ") for value in descriptions) and self.ui.visible_node(root, node):
                        candidates.append(node)
                assert len(candidates) <= 1, f"Ambiguous canvas target {label}"
                return candidates[0] if candidates else None
            node = self.wait(component, f"fitted native canvas component {label}")
            target = next(item for item in self.targets["targets"] if item["label"] == label)
            point = next(item for item in self.targets["pointerPoints"] if item["name"] == point_name and item["label"] == label)
            return node, pointer_screen_point(_bounds(node), target, point["at"])

        def momentary(self, details):
            label = details["label"]
            _, point = self.canvas_pointer(label, details["canvasPoint"])
            record = hold_pointer_with_probes(self.ui, point,
                {details["probe"]: details["held"]}, {details["probe"]: details["released"]},
                self.assert_outputs, lambda: self.ui.capture("io-held-" + label.lower()), details["holdSeconds"])
            record["component"] = label
            self.records.append(record)

        def pointers(self):
            self.fresh()
            case = self.contract["pointer"]
            self.momentary(case["button"])
            self.momentary(case["keypad"])
            joystick = case["joystick"]
            self.properties(joystick["label"])
            self.ui.click(text="→")
            self.wait_node("io_a11y_joystick", description=True, label=joystick["label"], x=14, y=0)
            self.close_properties()
            self.assert_outputs(joystick["right"])
            self.properties(joystick["label"])
            self.click("io_center")
            self.wait_node("io_a11y_joystick", description=True, label=joystick["label"], x=0, y=0)
            self.close_properties()
            self.assert_outputs(joystick["centered"])
            node, start = self.canvas_pointer(joystick["label"], joystick["canvasStartPoint"])
            target = next(item for item in self.targets["targets"] if item["label"] == joystick["label"])
            point = next(item for item in self.targets["pointerPoints"] if item["name"] == joystick["canvasEndPoint"])
            end = pointer_screen_point(_bounds(node), target, point["at"])
            record = hold_pointer_with_probes(self.ui, start, joystick["held"], joystick["centered"],
                self.assert_outputs, lambda: self.ui.capture("io-joystick-held"), 2.0, end=end)
            record["component"] = joystick["label"]
            self.records.append(record)
            self.checkpoint("io-pointer-controls")

        def keyboard_field(self):
            root = self.ui.screen()
            sheet = self.ui.tagged(root, "editor-inspector-sheet")
            assert sheet is not None
            fields = [node for node in sheet.iter("node") if node.get("class", "").endswith("EditText")
                      and node.get("enabled") != "false" and self.visible_in_scope(root, node, sheet)]
            assert len(fields) == 2, "Expected the I/O keyboard and the component Label editors"
            fields.sort(key=lambda node: _bounds(node)[1])
            return fields[0]

        def enter_keyboard(self, text):
            self.properties(self.contract["keyboard"]["label"])
            self.ui.input_text(self.keyboard_field(), text)
            self.hide_keyboard()
            assert keyboard_buffer_value(self.keyboard_field().get("text", ""), self.contract["keyboard"]["label"]) == text, "The real keyboard editor did not retain its complete text"
            self.close_properties()

        def step_char(self):
            for _ in range(self.contract["keyboard"]["halfCyclesPerCharacter"]):
                self.ui.tap(self.s.simulation_action("a11y_step_clock"))
                self.s.wait_editor()

        def text(self):
            self.fresh()
            case = self.contract["keyboard"]
            self.enter_keyboard(case["text"])
            self.assert_outputs(case["queued"])
            for checkpoint in ("afterFirst", "afterSecond"):
                self.step_char(); self.assert_outputs(case[checkpoint]["outputs"])
                self.ui.capture("io-tty-" + checkpoint)
                self.records.append({"recordType": "visual-review-required", "component": case["tty"],
                                     "expectedRows": case[checkpoint]["ttyRows"]})
            for checkpoint in ("backspace", "formFeed"):
                self.properties(case["label"])
                self.click(case[checkpoint]["controlKey"])
                self.close_properties()
                self.assert_outputs({"OUT_KEYBOARD_DATA": case[checkpoint]["data"], "OUT_KEYBOARD_AVAILABLE": "1"})
                self.step_char()
                self.ui.capture("io-tty-" + checkpoint)
                self.records.append({"recordType": "visual-review-required", "component": case["tty"],
                                     "expectedRows": case[checkpoint]["ttyRows"]})
            capacity = case["capacityEdit"]
            self.edit_property(case["label"], capacity["name"], capacity["value"])
            self.properties(case["label"])
            self.ui.input_text(self.keyboard_field(), capacity["text"])
            self.hide_keyboard()
            assert keyboard_buffer_value(self.keyboard_field().get("text", ""), case["label"]) == capacity["expected"], "The buffer must truncate at its configured capacity"
            self.close_properties()
            self.checkpoint("io-keyboard-capacity", {case["label"]: {"buflen": "4"}})

        def dimensions(self):
            self.fresh()
            for edit in self.contract["dimensionEdits"]:
                self.edit_property(edit["label"], edit["name"], edit["value"])
                self.checkpoint("io-dimension-" + edit["label"].lower() + "-" + edit["name"],
                                {edit["label"]: {edit["name"]: edit["value"]}})
                self.edit_property(edit["label"], edit["name"], edit["restore"])
                self.checkpoint("io-restored-" + edit["label"].lower() + "-" + edit["name"],
                                {edit["label"]: {edit["name"]: edit["restore"]}})
                self.assert_outputs(self.contract["baselineOutputs"])
            self.checkpoint("io-dimensions-restored")

    flow = IoShowcase(scenarios)
    actions = {"signals": flow.signals, "dip-last": flow.dip, "pointer-controls": flow.pointers,
               "keyboard-tty": flow.text, "dimensions": flow.dimensions}
    case_results = {name: "not_run" for name in selected_cases}
    try:
        for name in selected_cases:
            case_results[name] = "started"
            try:
                scenarios.run("io-showcase-" + name, actions[name])
            except Exception:
                case_results[name] = "failed"
                raise
            else:
                case_results[name] = "passed"
    finally:
        report = {"scope": "native-mounted-editor", "fixtureSha256": hashlib.sha256(flow.fixture_xml).hexdigest(),
                  "package": scenarios.ui.package, "serial": scenarios.ui.serial, "automatedObservations": flow.records,
                  "selectionScope": "all-cases" if set(selected_cases) == set(IO_CASE_NAMES) else "selected-cases",
                  "selectedCases": list(selected_cases), "notSelectedCases": [name for name in IO_CASE_NAMES if name not in selected_cases],
                  "caseResults": [{"case": name, "scenario": "io-showcase-" + name, "status": status} for name, status in case_results.items()],
                  "visualReview": "required", "requiredVisualReview": flow.contract["requiredVisualReview"],
                  "qualification": "No overall #44 completion is inferred from automated probes alone."}
        (scenarios.ui.output / "io-showcase-observations.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report
