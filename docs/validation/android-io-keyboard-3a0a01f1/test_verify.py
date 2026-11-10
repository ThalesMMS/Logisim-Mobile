"""Replay retained evidence and corrupt its contents after refreshing inventory hashes."""
import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import verifier


class RetainedKeyboardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name) / "proof"
        shutil.copytree(verifier.BASE, self.base)
        self.process = patch.object(subprocess, "run", side_effect=AssertionError("Device/process calls forbidden"))
        self.process.start()
        self.addCleanup(self.process.stop)

    def json(self, relative):
        return json.loads((self.base / relative).read_text())

    def save(self, relative, payload):
        (self.base / relative).write_text(json.dumps(payload, indent=2) + "\n")

    def refresh_inventory(self):
        receipt = self.json("receipt.json")
        for name, entry in receipt["files"].items():
            payload = (self.base / name).read_bytes()
            entry.update(bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
        self.save("receipt.json", receipt)
        (self.base / "SHA256SUMS").write_text("".join(
            entry["sha256"] + "  " + name + "\n" for name, entry in receipt["files"].items()))

    def reject(self):
        self.refresh_inventory()
        with self.assertRaises((AssertionError, ValueError, KeyError, ET.ParseError)):
            verifier.verify(self.base)

    def snapshot(self, number):
        return next((self.base / "raw").glob("focus-snapshot-*-" + str(number) + ".json"))

    def test_genuine_package_preserves_failure_and_progress(self):
        result = verifier.verify(self.base)
        self.assertEqual("FAILED", result["retainedCaseStatus"])
        self.assertEqual("PARTIAL_PROGRESS_ONLY", result["qualification"])
        self.assertEqual([5, 6], result["abSnapshots"])
        self.assertEqual(10, result["completeSnapshots"])

    def test_import_does_not_read_proof_or_start_a_process(self):
        source = Path(verifier.__file__).read_text()
        namespace = {"__name__": "offline_import_test", "__file__": verifier.__file__}
        with patch.object(Path, "read_text", side_effect=AssertionError("Global file read forbidden")), \
                patch.object(Path, "read_bytes", side_effect=AssertionError("Global file read forbidden")):
            exec(compile(source, verifier.__file__, "exec"), namespace)
        self.assertTrue(callable(namespace["verify"]))

    def mutate_snapshot_xml(self, number, change):
        path = self.snapshot(number)
        record = json.loads(path.read_text())
        bundle = record["bundle"]
        old_encoding = bundle["snapshotXmlBase64"]
        root = ET.fromstring(base64.b64decode(old_encoding))
        change(root)
        encoded = base64.b64encode(ET.tostring(root, encoding="utf-8")).decode()
        bundle["snapshotXmlBase64"] = encoded
        stdout = path.parent / record["stdout"]
        stdout.write_text(stdout.read_text().replace(old_encoding, encoded))
        (path.parent / record["xml"]).write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
        path.write_text(json.dumps(record, indent=2) + "\n")

    def test_AB_assertion_rejects_B_even_with_consistent_XML_bundle_and_new_hashes(self):
        def change(root):
            keyboard = next(n for n in root.iter("node") if n.get("class", "").endswith("EditText") and n.get("text") == "AB")
            keyboard.set("text", "B")
        self.mutate_snapshot_xml(5, change)
        self.reject()

    def test_AB_editor_from_another_package_is_rejected(self):
        def change(root):
            for node in root.iter("node"):
                if node.get("class", "").endswith("EditText"):
                    node.set("package", "another.application")
        self.mutate_snapshot_xml(5, change)
        self.reject()

    def test_visible_label_claim_requires_containment_in_its_window(self):
        path = self.base / "raw/failure-ui.xml"
        root = ET.parse(path).getroot()
        next(root.iter("node")).set("bounds", "[0,0][1080,1100]")
        path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
        self.reject()

    def test_wrong_real_probe_is_not_hidden_by_matching_declared_expectation(self):
        p = "raw/io-showcase-observations.json"
        observations = self.json(p)
        record = observations["automatedObservations"][2]
        record["expected"]["OUT_KEYBOARD_DATA"] = "???????"
        record["observedDescription"] = record["observedDescription"].replace("OUT_KEYBOARD_DATA: 1000010", "OUT_KEYBOARD_DATA: ???????")
        self.save(p, observations)
        self.reject()

    def test_source_change_cannot_be_authenticated_by_rehashing_manifest(self):
        p = "raw/source-android_editing_scenarios.py"
        (self.base / p).write_text((self.base / p).read_text() + "\n# changed selector\n")
        manifest = self.json("raw/manifest.json")
        manifest["scenarioSources"]["android_editing_scenarios.py"] = verifier.digest(self.base / p)
        self.save("raw/manifest.json", manifest)
        self.reject()

    def test_changed_installed_app_is_rejected(self):
        p = "raw/post-run-readback-cleanup.json"
        post = self.json(p)
        post["installedArtifactsAfter"][0]["sha256"] = "0" * 64
        self.save(p, post)
        self.reject()

    def test_full_pass_claim_is_rejected(self):
        receipt = self.json("receipt.json")
        receipt["status"] = "PASSED"
        receipt["qualification"] = "COMPLETE"
        self.save("receipt.json", receipt)
        self.reject()

    def test_unexecuted_capacity_cannot_be_promoted(self):
        receipt = self.json("receipt.json")
        receipt["case"]["notRun"].remove("capacity change/truncation")
        receipt["case"]["completed"].append("capacity change/truncation")
        self.save("receipt.json", receipt)
        self.reject()

    def test_snapshot_root_error_nonce_and_pid_changes_are_rejected(self):
        path = self.snapshot(6)
        original = path.read_bytes()
        for field, value in (("status", "root-unavailable"), ("nonce", ""), ("appPidAfter", "99999")):
            with self.subTest(field=field):
                path.write_bytes(original)
                record = json.loads(original)
                record[field] = value
                path.write_text(json.dumps(record, indent=2) + "\n")
                self.reject()
        path.write_bytes(original)

    def test_selector_geometry_disabled_and_scope_changes_are_rejected(self):
        path = self.base / "raw/failure-ui.xml"
        original = path.read_bytes()
        for mode in ("geometry", "disabled", "scope"):
            with self.subTest(mode=mode):
                root = ET.fromstring(original)
                parents = {c: p for p in root.iter() for c in p}
                label = next(n for n in root.iter("node") if n.get("text") == "ASCII backspace")
                target = parents[label]
                if mode == "geometry":
                    target.set("bounds", "[455,1122][858,1247]")
                elif mode == "disabled":
                    target.set("enabled", "false")
                else:
                    scope = next(n for n in root.iter("node") if n.get("resource-id") == "editor-inspector-sheet")
                    scope.set("resource-id", "another-sheet")
                path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
                self.reject()
        path.write_bytes(original)

    def test_missing_wire_is_rejected_after_inventory_is_updated(self):
        path = self.base / "raw/io-baseline.circ"
        root = ET.parse(path).getroot()
        circuit = root.find("circuit")
        circuit.remove(circuit.find("wire"))
        path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
        self.reject()

    def test_protocol_after_terminal_is_rejected_even_with_matching_bundle(self):
        path = self.snapshot(6)
        record = json.loads(path.read_text())
        record["bundle"]["lateResult"] = "not allowed"
        (path.parent / record["stdout"]).write_text(
            (path.parent / record["stdout"]).read_text() + "INSTRUMENTATION_RESULT: lateResult=not allowed\n")
        path.write_text(json.dumps(record, indent=2) + "\n")
        self.reject()

    def test_pinned_contract_cannot_be_changed_and_rehashed(self):
        path = "inputs/io-actions-v1.json"
        contract = self.json(path)
        contract["keyboard"]["text"] = "B"
        self.save(path, contract)
        self.reject()


if __name__ == "__main__":
    unittest.main()
