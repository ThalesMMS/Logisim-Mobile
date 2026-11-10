"""Exercise the portable verifier with rehashed semantic corruptions."""

import base64
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("editing_r3_verifier", HERE / "verify.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class PortableEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.packet = Path(self.directory.name) / "packet"
        shutil.copytree(HERE, self.packet)

    def rehash(self, *names):
        p = self.packet / "inventory.json"
        inv = json.loads(p.read_text())
        for name in names:
            f = self.packet / name
            inv["files"][name] = {"bytes": f.stat().st_size, "sha256": verifier.digest(f)}
        inv["bytes"] = sum(x["bytes"] for x in inv["files"].values())
        p.write_text(json.dumps(inv))

    def xml(self, name, mutate):
        path = self.packet / "raw" / name
        root = ET.fromstring(path.read_bytes())
        mutate(root)
        path.write_bytes(ET.tostring(root))
        self.rehash("raw/" + name)

    def test_default_packet_passes_without_native_execution(self):
        result = verifier.verify(self.packet)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(22, result["literalCheckpoints"])
        self.assertEqual(2, result["orInsertionCheckpoints"])
        self.assertEqual("NOT_RUN", result["nativeReplay"])

    def test_rehashed_missing_wire_is_rejected_by_the_historical_oracle(self):
        self.xml("editing-wire-undo.circ", lambda root: root.find("circuit").remove(root.find("circuit/wire")))
        with self.assertRaisesRegex(AssertionError, "Document differs from wired"):
            verifier.verify(self.packet)

    def test_rehashed_wrong_facing_is_rejected_by_the_historical_oracle(self):
        self.xml("editing-property-north.circ", lambda root: root.find("circuit/comp[@name='NOT Gate']/a[@name='facing']").set("val", "west"))
        with self.assertRaisesRegex(AssertionError, "Document differs from north"):
            verifier.verify(self.packet)

    def test_rehashed_wrong_or_attribute_is_rejected(self):
        self.xml("editing-touch-or.circ", lambda root: root.find("circuit/comp[@name='OR Gate']/a[@name='width']").set("val", "8"))
        with self.assertRaisesRegex(AssertionError, "incorrect attributes"):
            verifier.verify(self.packet)

    def test_rehashed_or_with_a_changed_existing_wire_is_rejected(self):
        self.xml("editing-accessible-or.circ", lambda root: root.find("circuit/wire").set("to", "(170,110)"))
        with self.assertRaisesRegex(AssertionError, "changed existing wires"):
            verifier.verify(self.packet)

    def test_different_fresh_or_identity_is_allowed_by_the_original_comparator(self):
        self.xml("editing-accessible-or.circ", lambda root: root.find("circuit/comp[@name='OR Gate']").set("logisim-mobile-id", "other-fresh-id"))
        self.assertEqual("PASS", verifier.verify(self.packet)["status"])

    def test_rehashed_project_option_change_is_rejected(self):
        self.xml("editing-final.circ", lambda root: root.find("options/a[@name='simlimit']").set("val", "1024"))
        with self.assertRaisesRegex(AssertionError, "Project options changed"):
            verifier.verify(self.packet)

    def test_rehashed_wrong_truth_probe_is_rejected_after_bundle_consistency(self):
        name = verifier.PREFIX + "64"
        xml_path = self.packet / "raw" / (name + ".xml")
        root = ET.fromstring(xml_path.read_bytes())
        canvas = next(n for n in root.iter("node") if n.get("content-desc", "").startswith("Circuit area."))
        canvas.set("content-desc", canvas.get("content-desc").replace("E2E_OUT: 0", "E2E_OUT: 1"))
        data = ET.tostring(root)
        xml_path.write_bytes(data)
        metadata = self.packet / "raw" / (name + ".json")
        record = json.loads(metadata.read_text())
        record["bundle"]["snapshotXmlBase64"] = base64.b64encode(data).decode()
        metadata.write_text(json.dumps(record))
        self.rehash("raw/" + name + ".xml", "raw/" + name + ".json")
        with self.assertRaisesRegex(AssertionError, "Canvas truth probe"):
            verifier.verify(self.packet)

    def test_rehashed_large_search_gesture_is_rejected(self):
        path = next(p for p in (self.packet / "raw").glob("editing-search-reveal-*.json")
                    if any("request" in r for r in json.loads(p.read_text())["records"]))
        record = json.loads(path.read_text())
        action = next(r["request"] for r in record["records"] if "request" in r)
        action["end"][1] = action["start"][1] + 950
        path.write_text(json.dumps(record))
        self.rehash(str(path.relative_to(self.packet)))
        with self.assertRaisesRegex(AssertionError, "bounded source"):
            verifier.verify(self.packet)

    def test_rehashed_native_settings_difference_is_rejected(self):
        path = self.packet / "control/unit.json"
        record = json.loads(path.read_text())
        record["settingsDifferences"] = {"font-scale": ["1.0", "2.0"]}
        path.write_text(json.dumps(record))
        self.rehash("control/unit.json")
        with self.assertRaisesRegex(AssertionError, "Postflight or cleanup"):
            verifier.verify(self.packet)

    def test_rehashed_historical_oracle_is_rejected_before_import(self):
        name = "scripts/user_scenario_contract.py"
        path = self.packet / "oracle-snapshot" / name
        path.write_text("raise RuntimeError('must not import')\n")
        manifest = self.packet / "oracle-snapshot/manifest.json"
        record = json.loads(manifest.read_text())
        record["files"][name] = {"bytes": path.stat().st_size, "sha256": verifier.digest(path)}
        manifest.write_text(json.dumps(record))
        self.rehash("oracle-snapshot/" + name, "oracle-snapshot/manifest.json")
        with self.assertRaisesRegex(AssertionError, "Historical source manifest changed"):
            verifier.verify(self.packet)

    def test_linked_evidence_directory_is_rejected(self):
        raw = self.packet / "raw"
        stored = self.packet.parent / "stored-raw"
        raw.rename(stored)
        raw.symlink_to(stored, target_is_directory=True)
        with self.assertRaisesRegex(AssertionError, "linked directory"):
            verifier.verify(self.packet)

    def test_packet_runs_after_relocation_outside_any_checkout(self):
        result = subprocess.run([sys.executable, "-B", str(self.packet / "verify.py")],
                                cwd=self.directory.name, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("PASS", json.loads(result.stdout)["status"])


if __name__ == "__main__":
    unittest.main()
