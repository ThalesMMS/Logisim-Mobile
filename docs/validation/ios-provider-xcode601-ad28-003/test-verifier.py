#!/usr/bin/env python3
"""Positive relocation and seven negative archive checks; never invoke native tools."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent


class ArchiveChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="logisim-registration-pass-archive-")
        self.addCleanup(self.temp.cleanup)
        self.copy = Path(self.temp.name) / "archive"
        shutil.copytree(ROOT, self.copy)

    def rehash(self):
        files = sorted(p for p in self.copy.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
        (self.copy / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(self.copy)}\n" for p in files))

    def replace(self, name, data):
        (self.copy / name).write_bytes(data)
        p = self.copy / "inventory.json"
        inventory = json.loads(p.read_text())
        for e in inventory["files"]:
            if e["path"] == name:
                e.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        p.write_text(json.dumps(inventory, indent=2) + "\n")

    def run_verifier(self):
        return subprocess.run([sys.executable, str(self.copy / "verify-archived-evidence.py")],
                              cwd=self.copy.parent, capture_output=True, text=True, timeout=30)

    def reject(self):
        self.rehash()
        self.assertNotEqual(self.run_verifier().returncode, 0)

    def test_relocated_archive_passes_with_limited_scope(self):
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["scope"], "BOOTSTRAP_REGISTER_ONLY")
        self.assertEqual(report["issue26"], "NOT_QUALIFIED")

    def test_functional_scope_claim_is_rejected(self):
        p = self.copy / "receipt.json"
        r = json.loads(p.read_text())
        r["limits"]["providerFunctionalExecution"] = "PASS"
        p.write_text(json.dumps(r, indent=2) + "\n")
        self.reject()

    def test_broadened_root_review_is_rejected(self):
        name = "evidence/run/ProvenanceArtifactReview.json"
        r = json.loads((self.copy / name).read_text())
        r["releaseApproved"] = True
        self.replace(name, (json.dumps(r, indent=2) + "\n").encode())
        self.reject()

    def test_matching_domain_count_change_is_rejected(self):
        name = "evidence/attachments/75BD609B-3A55-47BC-A34B-914DF1F29E5A.txt"
        b = (self.copy / name).read_bytes().replace(b"Matching fixture domains: 1.", b"Matching fixture domains: 2.")
        self.replace(name, b)
        self.reject()

    def test_historical_prefix_change_is_rejected(self):
        name = "evidence/prior-after-state.json"
        r = json.loads((self.copy / name).read_text())
        r["events"][5]["errorCode"] = -9999
        self.replace(name, (json.dumps(r, indent=2) + "\n").encode())
        self.reject()

    def test_new_completed_error_is_rejected_with_consistent_snapshots(self):
        db_name = "evidence/run/after-fixture-state.json"
        db = json.loads((self.copy / db_name).read_text())
        db["events"][-1]["errorCode"] = -2001
        payload = (json.dumps(db, indent=2) + "\n").encode()
        self.replace(db_name, payload)
        group_name = "evidence/run/after-group.json"
        group = json.loads((self.copy / group_name).read_text())
        group["events"] = db["events"]
        group["stateSHA256"] = hashlib.sha256(payload).hexdigest()
        self.replace(group_name, (json.dumps(group, indent=2) + "\n").encode())
        for filename in ("8F60315C-C430-47B1-A155-0FBE98F07501.txt", "75BD609B-3A55-47BC-A34B-914DF1F29E5A.txt", "1026E1B2-F645-4835-BED1-385B18B0BB15.txt"):
            name = "evidence/attachments/" + filename
            prefix, body = (self.copy / name).read_text().split("\nSNAPSHOT\n", 1)
            value = json.loads(body)
            value["events"][-1]["errorCode"] = -2001
            self.replace(name, (prefix + "\nSNAPSHOT\n" + json.dumps(value, indent=2) + "\n").encode())
        self.reject()

    def test_installed_der_change_is_rejected(self):
        name = "evidence/run/after-App-main-__ents_der.bin"
        self.replace(name, (self.copy / name).read_bytes()[:-1])
        self.reject()

    def test_duplicate_inventory_is_rejected(self):
        p = self.copy / "inventory.json"
        r = json.loads(p.read_text())
        r["files"].append(dict(r["files"][0]))
        p.write_text(json.dumps(r, indent=2) + "\n")
        self.reject()


if __name__ == "__main__":
    unittest.main()
