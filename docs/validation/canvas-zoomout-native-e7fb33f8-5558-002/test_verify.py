import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("failed_zoomout_native_archive", ROOT / "verify.py")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class FailedNativeArchiveTest(unittest.TestCase):
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
        raw = change(value)
        archived = gzip.compress(raw, mtime=0) if entry["encoding"] == "gzip" else raw
        path.write_bytes(archived)
        entry.update(rawBytes=len(raw), rawSha256=hashlib.sha256(raw).hexdigest(),
                     archiveBytes=len(archived), archiveSha256=hashlib.sha256(archived).hexdigest())
        (self.archive / "index.json").write_text(json.dumps(self.index))

    def rewrite_json(self, logical, change):
        def mutate(raw):
            value = json.loads(raw)
            change(value)
            return (json.dumps(value, indent=2) + "\n").encode()
        self.rewrite(logical, mutate)

    def test_portable_archive_never_reads_origin_paths(self):
        for entry in self.index["files"].values():
            entry["origin"] = "/not-mounted/native-producer"
        (self.archive / "index.json").write_text(json.dumps(self.index))
        self.assertEqual(185, VERIFY.verify(self.archive))

    def test_raw_tamper_is_rejected(self):
        path = self.archive / self.index["files"]["native002/native-runner.log"]["archive"]
        path.write_bytes(path.read_bytes() + b"tampered\n")
        with self.assertRaisesRegex(ValueError, "Archived hash"):
            VERIFY.verify(self.archive)

    def test_rehashed_native_success_is_rejected(self):
        self.rewrite_json("native002/unit.json", lambda value: value.update(status="PASS", nativeQualification="QUALIFIED"))
        with self.assertRaisesRegex(ValueError, "Native failure was promoted"):
            VERIFY.verify(self.archive)

    def test_rehashed_hidden_state_trace_loss_is_rejected(self):
        def change(value):
            value["state"]["total"] = 192
            value["state"]["dropped"] = 0
        self.rewrite_json(VERIFY.NATIVE + "canvas-input-trace.json", change)
        with self.assertRaisesRegex(ValueError, "State trace loss"):
            VERIFY.verify(self.archive)

    def test_rehashed_duplicate_poll_ordinal_is_rejected(self):
        def change(raw):
            lines = raw.splitlines(keepends=True)
            self.assertEqual(102, json.loads(lines[-3])["n"])
            lines[-3] = lines[-3].replace(b'"n":102', b'"n":101', 1)
            return b"".join(lines)
        self.rewrite(VERIFY.NATIVE + "zoom-out-observations.jsonl", change)
        with self.assertRaisesRegex(ValueError, "Retained comparison"):
            VERIFY.verify(self.archive)


if __name__ == "__main__":
    unittest.main()
