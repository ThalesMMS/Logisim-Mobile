#!/usr/bin/env python3
"""Exercise the verifier on relocated copies; no native tools or recorded paths."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent


class VerifierChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="logisim-existing-group-verifier-")
        self.addCleanup(self.temp.cleanup)
        self.copy = Path(self.temp.name) / "archive"
        shutil.copytree(ROOT, self.copy)

    def rehash(self):
        files = sorted(p for p in self.copy.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
        (self.copy / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(self.copy)}\n" for p in files))

    def run_verifier(self):
        return subprocess.run([sys.executable, str(self.copy / "verify-archived-evidence.py")],
                              cwd=self.copy.parent, capture_output=True, text=True, timeout=30)

    def test_relocated_archive_passes(self):
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["nativeResult"], "FAIL")

    def test_causal_claim_rejected_with_updated_checksums(self):
        p = self.copy / "receipt.json"
        receipt = json.loads(p.read_text())
        receipt["limits"]["rootCause"] = "SIGNING_CONFIRMED"
        p.write_text(json.dumps(receipt, indent=2) + "\n")
        self.rehash()
        self.assertNotEqual(self.run_verifier().returncode, 0)

    def test_query_result_rejection_order_change_rejected(self):
        p = self.copy / "discovery-selected.json"
        selected = json.loads(p.read_text())
        selected["events"][3], selected["events"][4] = selected["events"][4], selected["events"][3]
        p.write_text(json.dumps(selected, indent=2) + "\n")
        self.rehash()
        self.assertNotEqual(self.run_verifier().returncode, 0)

    def test_duplicate_inventory_entry_rejected_with_updated_checksums(self):
        p = self.copy / "inventory.json"
        inventory = json.loads(p.read_text())
        inventory["files"].append(dict(inventory["files"][0]))
        p.write_text(json.dumps(inventory, indent=2) + "\n")
        self.rehash()
        self.assertNotEqual(self.run_verifier().returncode, 0)

    def test_backend_change_rejected_with_updated_inventory(self):
        name = "evidence/backend-after.circ"
        p = self.copy / name
        value = bytearray(p.read_bytes())
        value[-1] ^= 1
        p.write_bytes(value)
        inventory_path = self.copy / "inventory.json"
        inventory = json.loads(inventory_path.read_text())
        for item in inventory["files"]:
            if item["path"] == name:
                item.update(bytes=len(value), sha256=hashlib.sha256(value).hexdigest())
        inventory_path.write_text(json.dumps(inventory, indent=2) + "\n")
        self.rehash()
        self.assertNotEqual(self.run_verifier().returncode, 0)


if __name__ == "__main__":
    unittest.main()
