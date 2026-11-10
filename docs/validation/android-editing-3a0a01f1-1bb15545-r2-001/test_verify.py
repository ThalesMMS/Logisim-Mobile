"""Keep the passed editing group distinct from the later wiring failure."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("editing_r2_verifier", BASE / "verifier.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


class EditingR2ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "relocated"
        shutil.copytree(BASE, self.root, ignore=shutil.ignore_patterns("__pycache__"))

    def tearDown(self):
        self.temp.cleanup()

    def rejected(self):
        inventory = json.loads((self.root / "inventory.json").read_text())
        for name in inventory:
            data = (self.root / name).read_bytes()
            inventory[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        write_json(self.root / "inventory.json", inventory)
        (self.root / "SHA256SUMS").write_text("".join(f'{value["sha256"]}  {name}\n' for name, value in sorted(inventory.items())))
        with self.assertRaises(AssertionError):
            verifier.verify(self.root)

    def test_relocation_preserves_the_mixed_result(self):
        result = verifier.verify(self.root)
        self.assertEqual((result["inventoryEntries"], result["sdkCompleteCaptures"], result["completedGroup"]), (417, 50, "editing-create-properties"))
        self.assertEqual(result["failedGroup"], "editing-wires-truth-table")
        self.assertEqual(len(result["notRun"]), 2)

    def test_reclassified_wiring_pass_rejected_after_rehash(self):
        path = self.root / "raw/results.json"
        data = json.loads(path.read_text())
        data[1]["status"] = "passed"
        write_json(path, data)
        self.rejected()

    def test_changed_public_north_export_rejected_after_rehash(self):
        path = self.root / "raw/editing-property-north.circ"
        path.write_bytes((self.root / "raw/editing-components.circ").read_bytes())
        self.rejected()

    def test_changed_search_clip_rejected_after_rehash(self):
        path = next((self.root / "raw").glob("focus-snapshot-*-49.json"))
        record = json.loads(path.read_text())
        old = record["bundle"]["snapshotXmlBase64"]
        tree = ET.fromstring(base64.b64decode(old))
        next(node for node in tree.iter("node") if node.get("resource-id") == "a11y-circuit-search").set("bounds", "[64,689][1016,811]")
        data = ET.tostring(tree)
        changed = base64.b64encode(data).decode()
        record["bundle"]["snapshotXmlBase64"] = changed
        write_json(path, record)
        (self.root / "raw" / record["xml"]).write_bytes(data)
        stdout = self.root / "raw" / record["stdout"]
        stdout.write_text(stdout.read_text().replace(old, changed))
        self.rejected()

    def test_changed_reverse_request_rejected_after_rehash(self):
        path = self.root / "controller/adb-command-requests.jsonl"
        data = [json.loads(line) for line in path.read_text().splitlines()]
        swipes = [item for item in data if item["argv"][3:6] == ["shell", "input", "swipe"]]
        swipes[-1]["argv"][6:] = ["540", "2035", "540", "1085", "250"]
        path.write_text("".join(json.dumps(item) + "\n" for item in data))
        self.rejected()


if __name__ == "__main__":
    unittest.main()
