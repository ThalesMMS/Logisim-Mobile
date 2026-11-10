"""Keep failure classification and partial document observations intact."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("editing_failure_verifier", BASE / "verifier.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


class EditingFailureArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "relocated"
        shutil.copytree(BASE, self.root, ignore=shutil.ignore_patterns("__pycache__"))

    def tearDown(self):
        self.temp.cleanup()

    def rejected(self):
        path = self.root / "inventory.json"
        inventory = json.loads(path.read_text())
        for name in inventory:
            data = (self.root / name).read_bytes()
            inventory[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        write_json(path, inventory)
        (self.root / "SHA256SUMS").write_text("".join(f'{value["sha256"]}  {name}\n' for name, value in sorted(inventory.items())))
        with self.assertRaises(AssertionError):
            verifier.verify(self.root)

    def test_relocation_preserves_failure(self):
        result = verifier.verify(self.root)
        self.assertEqual((result["status"], result["inventoryEntries"], result["sdkCompleteCaptures"]), ("FAIL_PRESERVED", 358, 42))
        self.assertEqual(len(result["notRun"]), 3)

    def test_reclassified_pass_rejected_after_rehash(self):
        path = self.root / "raw/results.json"
        data = json.loads(path.read_text())
        data[0]["status"] = "passed"
        write_json(path, data)
        self.rejected()

    def test_changed_complete_export_rejected_after_rehash(self):
        path = self.root / "raw/editing-components.circ"
        tree = ET.fromstring(path.read_bytes())
        attr = next(attr for comp in tree.findall("circuit/comp") for attr in comp.findall("a") if attr.get("name") == "label" and attr.get("val") == "E2E_NOT")
        attr.set("val", "WRONG")
        path.write_bytes(ET.tostring(tree))
        self.rejected()

    def test_changed_private_checkpoint_rejected_after_rehash(self):
        path = self.root / "raw/failure-checkpoint-36.circ"
        path.write_bytes((self.root / "raw/editing-components.circ").read_bytes())
        self.rejected()

    def test_changed_final_facing_rejected_after_rehash(self):
        path = self.root / "raw/failure-ui.xml"
        tree = ET.fromstring(path.read_bytes())
        next(node for node in tree.iter("node") if node.get("text") == "North").set("text", "South")
        path.write_bytes(ET.tostring(tree))
        self.rejected()


if __name__ == "__main__":
    unittest.main()
