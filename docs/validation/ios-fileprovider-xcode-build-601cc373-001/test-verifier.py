#!/usr/bin/env python3
"""Relocation and five negative checks on temporary copies; no native commands."""
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent


class ArchiveChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="logisim-601-archive-")
        self.addCleanup(self.temp.cleanup)
        self.copy = Path(self.temp.name) / "archive"
        shutil.copytree(ROOT, self.copy)

    def hashes(self):
        paths = sorted(p for p in self.copy.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
        (self.copy / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(self.copy)}\n" for p in paths))

    def replace_evidence(self, name, payload):
        (self.copy / name).write_bytes(payload)
        p = self.copy / "inventory.json"
        inventory = json.loads(p.read_text())
        for item in inventory["files"]:
            if item["path"] == name:
                item.update(bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
        p.write_text(json.dumps(inventory, indent=2) + "\n")
        self.hashes()

    def run_verifier(self):
        return subprocess.run([sys.executable, str(self.copy / "verify-archived-evidence.py")],
                              cwd=self.copy.parent, capture_output=True, text=True, timeout=20)

    def test_relocated_archive_passes_with_review_pending(self):
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["extraReview"], "PENDING_ROOT_REVIEW")
        self.assertEqual(report["nativeExecution"], "NOT_RUN")

    def test_forged_extra_approval_is_rejected(self):
        p = self.copy / "receipt.json"
        receipt = json.loads(p.read_text())
        receipt["additionalEntitlementReview"] = "APPROVED"
        p.write_text(json.dumps(receipt, indent=2) + "\n")
        self.hashes()
        self.assertNotEqual(self.run_verifier().returncode, 0)

    def test_signature_channel_cannot_be_replaced_with_section_dictionary(self):
        name = "evidence/artifact/entitlements-App.stdout.plist"
        payload = plistlib.dumps({"com.apple.security.application-groups": [
            "group.thalesmms.logisim.validation.revocationfixture"]})
        self.replace_evidence(name, payload)
        self.assertNotEqual(self.run_verifier().returncode, 0)

    def test_truncated_der_is_rejected(self):
        name = "evidence/artifact/Provider-main-__ents_der.bin"
        self.replace_evidence(name, (self.copy / name).read_bytes()[:-1])
        self.assertNotEqual(self.run_verifier().returncode, 0)

    def test_changed_packaging_input_is_rejected(self):
        name = "evidence/artifact/App-generated-RevocationFixture.app-Simulated.xcent"
        payload = (self.copy / name).read_bytes().replace(b"P4VGX44GD8", b"X4VGX44GD8")
        self.replace_evidence(name, payload)
        self.assertNotEqual(self.run_verifier().returncode, 0)

    def test_duplicate_inventory_entry_is_rejected(self):
        p = self.copy / "inventory.json"
        inventory = json.loads(p.read_text())
        inventory["files"].append(dict(inventory["files"][0]))
        p.write_text(json.dumps(inventory, indent=2) + "\n")
        self.hashes()
        self.assertNotEqual(self.run_verifier().returncode, 0)


if __name__ == "__main__":
    unittest.main()
