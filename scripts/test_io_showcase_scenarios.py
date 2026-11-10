"""Preflight the shared native I/O contract. These tests do not execute UI."""
from pathlib import Path
import unittest
import json
import tempfile
from contextlib import redirect_stdout
from io import StringIO
from copy import deepcopy
import xml.etree.ElementTree as ET
from android_io_showcase_scenarios import keyboard_buffer_value, hold_pointer_with_probes
from io_showcase_contract import (load_io_contract, assert_io_document, component_attributes,
    IoShowcaseOracle, assert_probe_description, pointer_screen_point, main as postflight_main)

ROOT = Path(__file__).resolve().parents[1]


class IoShowcaseScenarioTest(unittest.TestCase):
    def setUp(self):
        self.contract = load_io_contract()
        self.xml = (ROOT / self.contract["fixture"]).read_text()

    def test_fixture_contains_all_twelve_types_and_nineteen_named_binary_probes(self):
        project = assert_io_document(self.xml, self.contract)
        self.assertEqual(54, len(project.findall("circuit/comp")))
        self.assertEqual(64, len(project.findall("circuit/wire")))
        self.assertEqual(19, len(self.contract["baselineOutputs"]))
        for label, bits in self.contract["baselineOutputs"].items():
            kind, attrs = component_attributes(self.xml, label)
            self.assertEqual("Pin", kind)
            self.assertEqual("output", attrs["type"])
            self.assertEqual("2", attrs["radix"])
            self.assertEqual(len(bits), int(attrs["width"]))

    def test_fixture_defaults_match_keyboard_and_dimension_contract(self):
        _, keyboard = component_attributes(self.xml, "IO_KEYBOARD")
        self.assertEqual(str(self.contract["keyboard"]["capacity"]), keyboard["buflen"])
        for edit in self.contract["dimensionEdits"]:
            _, attrs = component_attributes(self.xml, edit["label"])
            self.assertEqual(edit["restore"], attrs[edit["name"]])

    def test_missing_types_and_wrong_persisted_positions_fail(self):
        project = ET.fromstring(self.xml)
        circuit = project.find("circuit")
        tty = next(comp for comp in circuit.findall("comp") if comp.get("name") == "TTY")
        circuit.remove(tty)
        with self.assertRaises(AssertionError):
            assert_io_document(ET.tostring(project), self.contract)
        with self.assertRaises(AssertionError):
            assert_io_document(self.xml, self.contract, {"IO_DIP": {"positions": "2"}})

    def test_keyboard_caption_normalization_cannot_hide_excess_input(self):
        self.assertEqual("ABCD", keyboard_buffer_value("ABCD", "IO_KEYBOARD"))
        self.assertEqual("ABCD", keyboard_buffer_value("ABCD\nIO_KEYBOARD · 4/4", "IO_KEYBOARD"))
        self.assertNotEqual("ABCD", keyboard_buffer_value("ABCDE", "IO_KEYBOARD"))
        self.assertNotEqual("ABCD", keyboard_buffer_value("ABCDE\nIO_KEYBOARD · 5/4", "IO_KEYBOARD"))

    def test_previous_joystick_fraction_cannot_claim_the_extreme_raw_value(self):
        invalid = deepcopy(self.contract)
        invalid["pointer"]["joystick"]["drag"]["x"] = 0.95
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.json"
            path.write_text(json.dumps(invalid))
            with self.assertRaisesRegex(ValueError, "13"):
                load_io_contract(path)
        self.assertEqual({"x": 0.99, "y": 0.01}, self.contract["pointer"]["joystick"]["drag"])

    def test_losing_all_wires_fails_even_with_valid_component_attributes(self):
        project = ET.fromstring(self.xml)
        circuit = project.find("circuit")
        for wire in list(circuit.findall("wire")):
            circuit.remove(wire)
        with self.assertRaisesRegex(AssertionError, "connectivity"):
            assert_io_document(ET.tostring(project), self.contract)

    def test_changing_one_connection_cannot_pass_with_the_same_wire_count(self):
        project = ET.fromstring(self.xml)
        wire = project.find("circuit/wire")
        wire.set("from", "(1,1)")
        wire.set("to", "(2,1)")
        self.assertEqual(64, len(project.findall("circuit/wire")))
        with self.assertRaisesRegex(AssertionError, "connectivity"):
            assert_io_document(ET.tostring(project), self.contract)

    def test_missing_restore_is_rejected_by_the_stateful_oracle(self):
        targets = json.loads((ROOT / "examples/io-showcase.targets.json").read_text())
        oracle = IoShowcaseOracle(self.xml, targets, self.contract)
        project = ET.fromstring(self.xml)
        tty = next(comp for comp in project.findall("circuit/comp") if comp.get("name") == "TTY")
        tty.find("a[@name='rows']").set("val", "4")
        changed = ET.tostring(project)
        oracle.expect({"IO_TTY": {"rows": "4"}})
        oracle.assert_document(changed)
        oracle.expect({"IO_TTY": {"rows": "3"}})
        with self.assertRaisesRegex(AssertionError, "attributes"):
            oracle.assert_document(changed)

    def test_dip_positions_compare_by_hex_value_in_either_spelling(self):
        targets = json.loads((ROOT / "examples/io-showcase.targets.json").read_text())
        oracle = IoShowcaseOracle(self.xml, targets, self.contract)
        project = ET.fromstring(self.xml)
        dip = next(comp for comp in project.findall("circuit/comp") if comp.get("name") == "DipSwitch")
        dip.find("a[@name='positions']").set("val", "0x0")
        oracle.assert_document(ET.tostring(project))
        dip.find("a[@name='positions']").set("val", "0x2")
        oracle.expect({"IO_DIP": {"positions": "2"}})
        oracle.assert_document(ET.tostring(project))
        dip.find("a[@name='positions']").set("val", "0x3")
        with self.assertRaisesRegex(AssertionError, "attributes"):
            oracle.assert_document(ET.tostring(project))

    def test_a_legitimate_reroute_may_change_segment_count_without_losing_ports(self):
        targets = json.loads((ROOT / "examples/io-showcase.targets.json").read_text())
        project = ET.fromstring(self.xml)
        circuit = project.find("circuit")
        point = lambda raw: tuple(map(int, raw.strip("()").split(",")))
        vertices = {tuple(port["at"]) for target in targets["targets"] for port in target["ports"]}
        vertices.update(point(value) for wire in circuit.findall("wire") for value in [wire.get("from"), wire.get("to")])
        for wire in circuit.findall("wire"):
            first, last = point(wire.get("from")), point(wire.get("to"))
            if first[1] != last[1]:
                continue
            if any(p not in [first, last] and p[1] == first[1] and min(first[0], last[0]) <= p[0] <= max(first[0], last[0]) for p in vertices):
                continue
            corner1, corner2 = (first[0], first[1] + 7), (last[0], last[1] + 7)
            fmt = lambda value: f"({value[0]},{value[1]})"
            wire.set("to", fmt(corner1))
            ET.SubElement(circuit, "wire", {"from": fmt(corner1), "to": fmt(corner2)})
            ET.SubElement(circuit, "wire", {"from": fmt(corner2), "to": fmt(last)})
            break
        else:
            self.fail("The reference fixture must expose a non-branching segment")
        self.assertEqual(66, len(circuit.findall("wire")))
        assert_io_document(ET.tostring(project), self.contract)

    def test_a_held_phase_cannot_pass_when_the_output_stays_low_and_always_releases(self):
        ui = FakePointerUi()
        captures = []
        with self.assertRaisesRegex(AssertionError, "OUT_BUTTON=1"):
            hold_pointer_with_probes(ui, (100, 100), {"OUT_BUTTON": "1"}, {"OUT_BUTTON": "0"},
                                     lambda expected: "Outputs OUT_BUTTON: 0.", lambda: captures.append(True), 0)
        self.assertEqual(["DOWN", "UP"], [command[4] for command in ui.commands])
        self.assertEqual([], captures)

    def test_a_held_phase_records_both_actual_states_and_the_owned_pointer(self):
        ui = FakePointerUi()
        observed = iter(["OUT_BUTTON: 0.", "OUT_BUTTON: 1.", "OUT_BUTTON: 0."])
        capture = []
        result = hold_pointer_with_probes(ui, (100, 100), {"OUT_BUTTON": "1"}, {"OUT_BUTTON": "0"},
                                         lambda expected: next(observed), lambda: capture.append(True), 0)
        self.assertEqual("OUT_BUTTON: 1.", result["heldDescription"])
        self.assertEqual("OUT_BUTTON: 0.", result["releasedDescription"])
        self.assertEqual([True], capture)
        self.assertEqual(["DOWN", "UP"], [command[4] for command in ui.commands])

    def test_probe_names_and_values_must_match_complete_boundaries(self):
        for wrong in ["NOT_OUT_BUTTON: 1.", "OUT_BUTTON: 10."]:
            with self.subTest(wrong=wrong), self.assertRaises(AssertionError):
                assert_probe_description(wrong, {"OUT_BUTTON": "1"})

    def test_canvas_pointer_uses_the_manifest_world_point_and_observed_frame(self):
        target = {"bounds": [410, 310, 440, 340]}
        self.assertEqual((390, 210), pointer_screen_point((100, 200, 400, 500), target, [439, 311]))
        with self.assertRaises(AssertionError):
            pointer_screen_point((100, 200, 400, 500), target, [450, 311])

    def run_postflight(self, document, expected=None, *, attachment="1-baseline.circ", metadata_document=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = {"schemaVersion": 1, "scope": "native-mounted-editor",
                        "document": metadata_document or attachment,
                        "expectedAttributes": expected or {}, "postflightRequired": True}
            (root / "checkpoint.json").write_text(json.dumps(metadata))
            (root / "extracted-uuid.circ").write_text(document)
            with redirect_stdout(StringIO()):
                status = postflight_main([
                    "--metadata", str(root / "checkpoint.json"),
                    "--document", str(root / "extracted-uuid.circ"),
                    "--document-attachment-name", attachment,
                    "--output", str(root / "postflight.json")])
            return status, json.loads((root / "postflight.json").read_text())

    def test_postflight_validates_the_exact_attachment_without_inferring_native_execution(self):
        status, record = self.run_postflight(self.xml)
        self.assertEqual(0, status)
        self.assertEqual("passed", record["status"])
        self.assertEqual("not-inferred", record["nativeExecution"])
        self.assertEqual(54, record["components"])
        self.assertEqual(64, record["wireSegments"])
        self.assertEqual({"metadata", "document", "fixture", "targets", "contract"}, set(record["sha256"]))

    def test_postflight_rejects_mismatched_attachment_names(self):
        status, record = self.run_postflight(self.xml, metadata_document="2-different.circ")
        self.assertEqual(1, status)
        self.assertIn("attachment names differ", record["error"])

    def test_postflight_rejects_lost_wires_and_incomplete_restoration(self):
        project = ET.fromstring(self.xml)
        circuit = project.find("circuit")
        for wire in list(circuit.findall("wire")):
            circuit.remove(wire)
        status, record = self.run_postflight(ET.tostring(project, encoding="unicode"))
        self.assertEqual(1, status)
        self.assertIn("connectivity", record["error"])
        project = ET.fromstring(self.xml)
        tty = next(comp for comp in project.findall("circuit/comp") if comp.get("name") == "TTY")
        tty.find("a[@name='rows']").set("val", "4")
        status, record = self.run_postflight(ET.tostring(project, encoding="unicode"), {"IO_TTY": {"rows": "3"}})
        self.assertEqual(1, status)
        self.assertIn("attributes", record["error"])


class FakePointerUi:
    def __init__(self):
        self.commands = []

    def _require_test_app(self):
        pass

    def adb(self, *arguments):
        self.commands.append(arguments)


if __name__ == "__main__":
    unittest.main()
