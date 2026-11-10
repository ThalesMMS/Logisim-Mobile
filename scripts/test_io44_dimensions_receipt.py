"""Negative controls for the retained dimensions evidence, without Android access."""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_io44_dimensions_receipt import DEFAULT_BUNDLE, sha256, verify_bundle


class DimensionsReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.bundle = Path(self.temporary.name) / "bundle"
        shutil.copytree(DEFAULT_BUNDLE, self.bundle)

    def replace_artifact(self, name, data):
        path = self.bundle / "evidence" / name
        path.write_bytes(data)
        receipt_path = self.bundle / "receipt.json"
        receipt = json.loads(receipt_path.read_text())
        receipt["artifacts"]["evidence/" + name] = sha256(path)
        receipt_path.write_text(json.dumps(receipt))

    def change_json(self, name, change):
        value = json.loads((self.bundle / "evidence" / name).read_text())
        change(value)
        self.replace_artifact(name, json.dumps(value).encode())

    def change_xml(self, name, change):
        value = ET.parse(self.bundle / "evidence" / name).getroot()
        change(value)
        self.replace_artifact(name, ET.tostring(value))

    def test_original_documents_and_nineteen_probes_verify_in_the_bounded_scope(self):
        result = verify_bundle(self.bundle)
        self.assertEqual("passed", result["status"])
        self.assertEqual("dimensions-only", result["scope"])
        self.assertEqual("partial", result["issue44"])
        self.assertFalse(result["nativeReexecuted"])
        self.assertEqual(19, result["baselineProbes"])
        self.assertEqual(6, result["nativeProbeRecords"])
        self.assertEqual(12, len(result["checkpoints"]))
        self.assertEqual(80, next(item["wireSegments"] for item in result["checkpoints"]
                                 if item["checkpoint"] == "io-dimension-io_keypad-size"))
        self.assertEqual(64, result["checkpoints"][-1]["wireSegments"])

    def test_export_with_valid_attributes_and_no_wires_is_rejected_after_rehashing(self):
        def remove_wires(project):
            circuit = project.find("circuit")
            for wire in list(circuit.findall("wire")):
                circuit.remove(wire)
        self.change_xml("io-dimension-io_keypad-size.circ", remove_wires)
        with self.assertRaisesRegex(AssertionError, "connectivity"):
            verify_bundle(self.bundle)

    def test_changed_connection_with_the_same_segment_count_is_rejected(self):
        def disconnect(project):
            wire = project.find("circuit/wire")
            wire.set("from", "(1,1)")
            wire.set("to", "(2,1)")
        self.change_xml("io-restored-io_keypad-size.circ", disconnect)
        with self.assertRaisesRegex(AssertionError, "connectivity"):
            verify_bundle(self.bundle)

    def test_missing_dimension_attribute_is_rejected_after_rehashing(self):
        def remove_rows(project):
            tty = next(node for node in project.findall("circuit/comp") if node.get("name") == "TTY")
            tty.remove(tty.find("a[@name='rows']"))
        self.change_xml("io-restored-io_tty-rows.circ", remove_rows)
        with self.assertRaisesRegex(AssertionError, "attributes"):
            verify_bundle(self.bundle)

    def test_keypad_that_did_not_restore_its_size_is_rejected(self):
        def keep_normal(project):
            keypad = next(node for node in project.findall("circuit/comp") if node.get("name") == "MatrixKeypad")
            keypad.find("a[@name='size']").set("val", "normal")
        self.change_xml("io-restored-io_keypad-size.circ", keep_normal)
        with self.assertRaisesRegex(AssertionError, "attributes"):
            verify_bundle(self.bundle)

    def test_dimensions_receipt_cannot_be_promoted_to_full_issue44(self):
        path = self.bundle / "receipt.json"
        receipt = json.loads(path.read_text())
        receipt["claim"]["issue44"] = "passed"
        receipt["claim"]["allIssue44CasesPassed"] = True
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(AssertionError, "dimensions only"):
            verify_bundle(self.bundle)

    def test_selected_case_receipt_cannot_claim_full_suite_execution(self):
        self.change_json("io-showcase-observations.json", lambda record: record.update(selectionScope="full-suite"))
        with self.assertRaisesRegex(AssertionError, "Selection"):
            verify_bundle(self.bundle)

    def test_unknown_keyboard_probe_does_not_pass_the_native_baseline(self):
        def unknown(record):
            entry = record["automatedObservations"][0]
            entry["observedDescription"] = entry["observedDescription"].replace("OUT_KEYBOARD_DATA: 0000000", "OUT_KEYBOARD_DATA: ???????")
        self.change_json("io-showcase-observations.json", unknown)
        with self.assertRaisesRegex(AssertionError, "OUT_KEYBOARD_DATA"):
            verify_bundle(self.bundle)

    def test_paired_hierarchy_requires_the_actual_complete_probe_description(self):
        name = "io-dimensions-restored-ui.xml"
        text = (self.bundle / "evidence" / name).read_text()
        self.assertIn("OUT_KEYBOARD_DATA: 0000000", text)
        self.replace_artifact(name, text.replace("OUT_KEYBOARD_DATA: 0000000", "OUT_KEYBOARD_DATA: ???????").encode())
        with self.assertRaisesRegex(AssertionError, "complete native nineteen-probe"):
            verify_bundle(self.bundle)

    def test_changed_snapshot_apk_after_execution_is_rejected(self):
        self.change_json("manifest.json", lambda record: record["installedArtifactsAfter"][-1].update(sha256="0" * 64))
        with self.assertRaisesRegex(AssertionError, "installedArtifactsAfter"):
            verify_bundle(self.bundle)

    def test_modified_executor_is_rejected_even_when_the_bundle_hash_is_updated(self):
        name = "source-android_io_showcase_scenarios.py"
        self.replace_artifact(name, (self.bundle / "evidence" / name).read_bytes() + b"\n# modified\n")
        with self.assertRaisesRegex(AssertionError, "Frozen executor mismatch"):
            verify_bundle(self.bundle)

    def test_omitted_checkpoint_cannot_pass_with_a_smaller_inventory(self):
        path = self.bundle / "receipt.json"
        receipt = json.loads(path.read_text())
        del receipt["artifacts"]["evidence/io-dimension-io_keypad-size.circ"]
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(AssertionError, "Incomplete evidence inventory"):
            verify_bundle(self.bundle)

    def test_missing_historical_oracle_input_is_rejected(self):
        (self.bundle / "oracle-snapshot/examples/io-showcase.circ").unlink()
        with self.assertRaisesRegex(AssertionError, "Historical oracle inventory"):
            verify_bundle(self.bundle)

    def test_modified_historical_oracle_is_rejected_before_execution(self):
        path = self.bundle / "oracle-snapshot/scripts/io_showcase_contract.py"
        path.write_bytes(path.read_bytes() + b"\nraise RuntimeError('Tampered oracle executed')\n")
        with self.assertRaisesRegex(AssertionError, "Historical oracle hash mismatch"):
            verify_bundle(self.bundle)

    def test_rehashing_snapshot_and_receipt_cannot_replace_the_pinned_oracle(self):
        snapshot = self.bundle / "oracle-snapshot"
        path = snapshot / "scripts/io_showcase_contract.py"
        path.write_bytes(path.read_bytes() + b"\n# changed historical source\n")
        receipt_path = self.bundle / "receipt.json"
        receipt = json.loads(receipt_path.read_text())
        receipt["oracleInputs"]["scripts/io_showcase_contract.py"] = sha256(path)
        receipt_path.write_text(json.dumps(receipt))
        manifest_path = snapshot / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"] = receipt["oracleInputs"]
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(AssertionError, "Historical oracle manifest changed"):
            verify_bundle(self.bundle)

    def test_historical_pin_must_match_the_fixed_audit_pin(self):
        path = self.bundle / "oracle-snapshot/manifest.json"
        manifest = json.loads(path.read_text())
        manifest["sourcePin"] = "0" * 40
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(AssertionError, "Historical oracle pin changed"):
            verify_bundle(self.bundle)

    def test_receipt_cannot_select_another_audit_pin(self):
        path = self.bundle / "receipt.json"
        receipt = json.loads(path.read_text())
        receipt["auditBasePin"] = "0" * 40
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(AssertionError, "Historical oracle pin changed"):
            verify_bundle(self.bundle)

    def test_historical_oracle_cannot_link_to_an_external_file(self):
        path = self.bundle / "oracle-snapshot/examples/io-showcase.circ"
        external = Path(self.temporary.name) / "external.circ"
        path.rename(external)
        path.symlink_to(external)
        with self.assertRaisesRegex(AssertionError, "cannot use symlinks"):
            verify_bundle(self.bundle)

    def test_current_sources_and_pause_contract_do_not_affect_historical_replay(self):
        root = Path(self.temporary.name) / "current-checkout"
        scripts = root / "scripts"
        scripts.mkdir(parents=True)
        verifier = Path(__file__).with_name("verify_io44_dimensions_receipt.py")
        shutil.copy2(verifier, scripts / verifier.name)
        (scripts / "io_showcase_contract.py").write_text("raise RuntimeError('Current oracle imported')\n")
        for name in ("examples/io-showcase.circ", "examples/io-showcase.targets.json",
                     "fixtures/user-scenarios/io-actions-v1.json"):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("changed current source\n")
        current_contract = root / "fixtures/user-scenarios/editor-actions-v1.json"
        current_contract.write_text(json.dumps({"sap1": {"pauseObservationMilliseconds": 2500}}))
        result = subprocess.run([sys.executable, "-B", str(scripts / verifier.name), "--bundle", str(self.bundle)],
                                capture_output=True, text=True, check=True)
        self.assertEqual("passed", json.loads(result.stdout)["status"])


if __name__ == "__main__":
    unittest.main()
