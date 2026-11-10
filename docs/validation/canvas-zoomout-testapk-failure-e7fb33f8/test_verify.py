import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("failed_testapk_archive", ROOT / "verify.py")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class FailedBuildArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.archive = Path(self.temporary.name) / "portable"
        shutil.copytree(ROOT, self.archive, ignore=shutil.ignore_patterns("__pycache__"))
        self.index = json.loads((self.archive / "index.json").read_bytes())

    def tearDown(self):
        self.temporary.cleanup()

    def rewrite(self, logical, change):
        entry = self.index["files"][logical]
        path = self.archive / entry["archive"]
        value = gzip.decompress(path.read_bytes()) if entry["encoding"] == "gzip" else path.read_bytes()
        value = json.loads(value)
        change(value)
        raw = (json.dumps(value, indent=2) + "\n").encode()
        archived = gzip.compress(raw, mtime=0) if entry["encoding"] == "gzip" else raw
        path.write_bytes(archived)
        entry.update(rawBytes=len(raw), rawSha256=hashlib.sha256(raw).hexdigest(),
                     archiveBytes=len(archived), archiveSha256=hashlib.sha256(archived).hexdigest())
        (self.archive / "index.json").write_text(json.dumps(self.index))

    def test_portable_copy_verifies_without_original_paths(self):
        for entry in self.index["files"].values():
            entry["origin"] = "/not-mounted/original-producer"
        (self.archive / "index.json").write_text(json.dumps(self.index))
        self.assertEqual(18, VERIFY.verify(self.archive))

    def test_raw_tamper_is_rejected(self):
        path = self.archive / self.index["files"]["failed-build/test-apk-build.log"]["archive"]
        path.write_bytes(path.read_bytes() + b"changed\n")
        with self.assertRaisesRegex(ValueError, "Archived byte hash"):
            VERIFY.verify(self.archive)

    def test_rehashed_false_success_is_rejected(self):
        self.rewrite("failed-build/artifact.json", lambda value: value.update(status="PASS"))
        with self.assertRaisesRegex(ValueError, "reclassified"):
            VERIFY.verify(self.archive)

    def test_rehashed_incomplete_guard_graph_is_rejected(self):
        self.rewrite("failed-build/guard-build-task-graph.json", lambda value: value["tasks"].pop())
        with self.assertRaisesRegex(ValueError, "37-task graph"):
            VERIFY.verify(self.archive)

    def test_rehashed_nonzero_gzip_mtime_is_rejected(self):
        entry = self.index["files"]["failed-build/artifact.json"]
        path = self.archive / entry["archive"]
        changed = bytearray(path.read_bytes())
        changed[4:8] = (1).to_bytes(4, "little")
        path.write_bytes(changed)
        entry["archiveSha256"] = hashlib.sha256(changed).hexdigest()
        (self.archive / "index.json").write_text(json.dumps(self.index))
        with self.assertRaisesRegex(ValueError, "Gzip metadata"):
            VERIFY.verify(self.archive)


if __name__ == "__main__":
    unittest.main()
