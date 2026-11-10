#!/usr/bin/env python3
"""Exercise semantic rejection after an attacker recomputes file hashes."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("idle16_receipt", HERE / "verify.py")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class ReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "receipt"
        shutil.copytree(HERE, self.directory, ignore=shutil.ignore_patterns("__pycache__"))

    def rewrite_raw(self, name, payload):
        archive = self.directory / "raw-attempt.zip"
        with zipfile.ZipFile(archive) as source:
            entries = [(copy.copy(info), source.read(info.filename)) for info in source.infolist()]
        old = next(data for info, data in entries if info.filename == name)
        self.assertEqual(len(payload), len(old), "Keep fixed byte totals to reach semantic checks")
        with zipfile.ZipFile(archive, "w") as output:
            for info, data in entries:
                output.writestr(info, payload if info.filename == name else data)
        manifest_path = self.directory / "raw-manifest.json"
        manifest = json.loads(manifest_path.read_bytes())
        manifest["files"][name] = {"bytes": len(payload), "sha256": VERIFIER.digest(payload)}
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        if (self.directory / name).is_file():
            (self.directory / name).write_bytes(payload)
        receipt_path = self.directory / "receipt.json"
        receipt = json.loads(receipt_path.read_bytes())
        for key, path in (("raw_archive", archive), ("raw_manifest", manifest_path)):
            receipt[key]["bytes"] = path.stat().st_size
            receipt[key]["sha256"] = VERIFIER.digest(path.read_bytes())
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")

    def rewrite_attempt(self, change):
        with zipfile.ZipFile(self.directory / "raw-attempt.zip") as archive:
            original = archive.read("attempt.json")
        attempt = json.loads(original)
        change(attempt)
        payload = json.dumps(attempt).encode().ljust(len(original), b" ")
        self.rewrite_raw("attempt.json", payload)

    def test_original_failure_is_verifiable(self):
        self.assertEqual(VERIFIER.verify(self.directory)["sample_windows"], 0)

    def test_relabeling_the_receipt_as_pass_fails(self):
        path = self.directory / "receipt.json"
        receipt = json.loads(path.read_bytes())
        receipt["status"] = "PASS"
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "observed outcome"):
            VERIFIER.verify(self.directory)

    def test_invented_collector_rejected_after_rehash(self):
        self.rewrite_attempt(lambda attempt: attempt.update(collector_argv=[]))
        with self.assertRaisesRegex(ValueError, "Collector was not reached"):
            VERIFIER.verify(self.directory)

    def test_unclean_return_rejected_after_rehash(self):
        self.rewrite_attempt(lambda attempt: attempt["owned_app_cleanup"][0].update(pid_after="1234"))
        with self.assertRaisesRegex(ValueError, "owned app remained running"):
            VERIFIER.verify(self.directory)

    def test_wrong_final_document_rejected_after_rehash(self):
        with zipfile.ZipFile(self.directory / "raw-attempt.zip") as archive:
            original = archive.read("adb/0077-stdout")
            call_log = archive.read("adb-calls.json")
        replacement = original.replace(b"gates-16", b"gates-64")
        self.rewrite_raw("adb/0077-stdout", replacement)
        calls = json.loads(call_log)
        calls[76]["stdout"]["sha256"] = VERIFIER.digest(replacement)
        self.rewrite_raw("adb-calls.json", (json.dumps(calls, indent=2, ensure_ascii=False) + "\n").encode())
        with self.assertRaisesRegex(ValueError, "Final observed UI differs"):
            VERIFIER.verify(self.directory)


if __name__ == "__main__":
    unittest.main()
