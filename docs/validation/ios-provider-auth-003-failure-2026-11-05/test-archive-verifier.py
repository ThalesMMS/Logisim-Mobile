#!/usr/bin/env python3
"""Replay the archive after relocation and reject rehashed false claims."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
from types import ModuleType
import unittest
from unittest.mock import patch

PACKAGE = Path(__file__).resolve().parent
verifier = ModuleType("auth003_archive_verifier")
exec(compile((PACKAGE / "verify-archived-evidence.py").read_bytes(), str(PACKAGE / "verify-archived-evidence.py"), "exec"), verifier.__dict__)


def checksums(root):
    files = sorted(f for f in root.rglob("*") if f.is_file() and f.name != "SHA256SUMS")
    (root / "SHA256SUMS").write_text("".join(hashlib.sha256(f.read_bytes()).hexdigest() + "  " + f.relative_to(root).as_posix() + "\n" for f in files))


def change_evidence(root, key, transform):
    inventory = json.loads((root / "inventory.json").read_bytes())
    entry, = [item for item in inventory["archived"] if item["key"] == key]
    path = root / entry["path"]
    data = gzip.decompress(path.read_bytes()) if entry["compression"] else path.read_bytes()
    data = transform(data)
    stored = gzip.compress(data, mtime=0) if entry["compression"] else data
    path.write_bytes(stored)
    entry.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), storedBytes=len(stored), storedSHA256=hashlib.sha256(stored).hexdigest())
    (root / "inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")


def modified_json(transform):
    def apply(data):
        value = json.loads(data)
        transform(value)
        return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
    return apply


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / "relocated-package"
        shutil.copytree(PACKAGE, self.root)

    def reject(self, expression):
        checksums(self.root)
        with self.assertRaisesRegex(ValueError, expression):
            verifier.verify(self.root)

    def test_relocated_package_reads_only_its_own_files(self):
        read_bytes, read_text = Path.read_bytes, Path.read_text

        def local_bytes(path, *args, **kwargs):
            self.assertTrue(path.resolve().is_relative_to(self.root.resolve()), "Verifier read an original external path")
            return read_bytes(path, *args, **kwargs)

        def local_text(path, *args, **kwargs):
            self.assertTrue(path.resolve().is_relative_to(self.root.resolve()), "Verifier read an original external path")
            return read_text(path, *args, **kwargs)

        with patch.object(Path, "read_bytes", local_bytes), patch.object(Path, "read_text", local_text):
            result = verifier.verify(self.root)
        self.assertEqual("ARCHIVE_REPLAY_PASS", result["status"])
        self.assertEqual("FAILED_SELECTED_CASE", result["nativeRun"])

    def test_rehashed_pass_classification_is_rejected(self):
        path = self.root / "receipt.json"
        value = json.loads(path.read_bytes())
        value["status"] = "PASS"
        path.write_text(json.dumps(value))
        self.reject("Archive must remain a failed selected case")

    def test_rehashed_qualified_raw_result_is_rejected(self):
        change_evidence(self.root, "run/results.json", modified_json(lambda value: value.update(qualification_gate="PARTIAL")))
        self.reject("Raw failure gates were reclassified")

    def test_rehashed_postflight_fingerprint_change_is_rejected(self):
        def change(value):
            value["installedAfterTest"]["Main"]["bundle_tree_sha256"] = "0" * 64
        change_evidence(self.root, "run/results.json", modified_json(change))
        self.reject("Installed bundle fingerprint differs")

    def test_rehashed_later_scenario_observation_is_rejected(self):
        receipt = json.loads((self.root / "receipt.json").read_bytes())
        def change(value):
            value["observations"].append({"phase": "denied-save"})
        change_evidence(self.root, receipt["publicBaseline"]["scenario"], modified_json(change))
        self.reject("Scenario must remain incomplete")

    def test_rehashed_import_report_removal_is_rejected(self):
        receipt = json.loads((self.root / "receipt.json").read_bytes())
        change_evidence(self.root, receipt["failure"]["tree"], lambda data: data.replace(b"Source version:", b"Changed version:"))
        self.reject("Missing final import-report metadata")

    def test_rehashed_activation_not_run_claim_is_rejected(self):
        path = self.root / "receipt.json"
        value = json.loads(path.read_bytes())
        value["observedProgress"]["turnOnTapped"] = False
        path.write_text(json.dumps(value))
        self.reject("Activation checkpoint differs")

    def test_rehashed_post_state_claim_is_rejected(self):
        path = self.root / "receipt.json"
        value = json.loads(path.read_bytes())
        value["postflight"]["providerAuthenticationState"] = "UNCHANGED_ALLOWED"
        path.write_text(json.dumps(value))
        self.reject("Baseline was promoted to post-state")

    def test_missing_critical_screenshot_is_rejected(self):
        receipt = json.loads((self.root / "receipt.json").read_bytes())
        inventory = json.loads((self.root / "inventory.json").read_bytes())
        entry, = [e for e in inventory["archived"] if e["key"] == receipt["failure"]["screenshot"]]
        (self.root / entry["path"]).unlink()
        checksums(self.root)
        with self.assertRaises(OSError):
            verifier.verify(self.root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
