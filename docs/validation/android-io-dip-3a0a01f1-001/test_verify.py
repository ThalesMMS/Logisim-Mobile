"""Small semantic negative controls for this immutable Native receipt."""
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
spec = importlib.util.spec_from_file_location("dip_archive_verifier", BASE / "verifier.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def rehash(root):
    inventory = json.loads((root / "inventory.json").read_text())
    for name in inventory:
        data = (root / name).read_bytes()
        inventory[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    write_json(root / "inventory.json", inventory)
    (root / "SHA256SUMS").write_text("".join(f'{value["sha256"]}  {name}\n' for name, value in sorted(inventory.items())))


class DipReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "relocated"
        shutil.copytree(BASE, self.root, ignore=shutil.ignore_patterns("__pycache__"))

    def tearDown(self):
        self.temp.cleanup()

    def rejected(self):
        rehash(self.root)
        with self.assertRaises(AssertionError):
            verifier.verify(self.root)

    def test_relocated_archive_passes(self):
        result = verifier.verify(self.root)
        self.assertEqual((result["retainedFiles"], result["sdkCompleteCaptures"], result["probeRecords"]), (218, 22, 4))

    def test_changed_probe_rejected_after_rehash(self):
        path = self.root / "raw/io-showcase-observations.json"
        value = json.loads(path.read_text())
        value["automatedObservations"][1]["observedDescription"] = value["automatedObservations"][1]["observedDescription"].replace("OUT_DIP1: 1", "OUT_DIP1: 0")
        write_json(path, value)
        self.rejected()

    def test_changed_document_rejected_after_rehash(self):
        for name in ("io-dip-four.circ", "io-dip-invalid-preserved.circ"):
            path = self.root / "raw" / name
            tree = ET.fromstring(path.read_bytes())
            component = next(node for node in tree.findall("circuit/comp") if any(attr.get("name") == "label" and attr.get("val") == "IO_DIP" for attr in node.findall("a")))
            next(attr for attr in component.findall("a") if attr.get("name") == "number").set("val", "3")
            path.write_bytes(ET.tostring(tree))
        self.rejected()

    def test_extra_invocation_rejected_after_rehash(self):
        path = self.root / "controller/unit.json"
        value = json.loads(path.read_text())
        value["nativeInvocations"] = 2
        write_json(path, value)
        self.rejected()

    def test_enabled_clickable_owner_rejected_after_rehash(self):
        path = next((self.root / "raw").glob("focus-snapshot-*-14.json"))
        value = json.loads(path.read_text())
        tree = ET.fromstring(base64.b64decode(value["bundle"]["snapshotXmlBase64"]))
        parents = {child: parent for parent in tree.iter() for child in parent}
        leaf = next(node for node in tree.iter("node") if node.get("text") == "Apply")
        parents[leaf].set("enabled", "true")
        original = value["bundle"]["snapshotXmlBase64"]
        changed = base64.b64encode(ET.tostring(tree)).decode()
        value["bundle"]["snapshotXmlBase64"] = changed
        write_json(path, value)
        (self.root / "raw" / value["xml"]).write_bytes(ET.tostring(tree))
        stdout = self.root / "raw" / value["stdout"]
        stdout.write_text(stdout.read_text().replace(original, changed))
        self.rejected()


if __name__ == "__main__":
    unittest.main()
