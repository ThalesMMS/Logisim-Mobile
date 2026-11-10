import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("testapk_build_archive", ROOT / "verify.py")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class TestApkBuildArchiveTest(unittest.TestCase):
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

    def test_portable_verification_does_not_read_origin_or_local_artifact_paths(self):
        for entry in self.index["files"].values():
            entry["origin"] = "/not-mounted/producer"
        for reference in self.index["localArtifacts"]:
            reference["path"] = "/not-mounted/local-artifact"
        (self.archive / "index.json").write_text(json.dumps(self.index))
        self.assertEqual(21, VERIFY.verify(self.archive))
        with self.assertRaises(FileNotFoundError):
            VERIFY.verify(self.archive, local_artifacts=True)

    def test_raw_log_tamper_is_rejected(self):
        path = self.archive / self.index["files"]["test-apk-build.log"]["archive"]
        path.write_bytes(path.read_bytes() + b"tampered\n")
        with self.assertRaisesRegex(ValueError, "Archived hash"):
            VERIFY.verify(self.archive)

    def test_rehashed_marker_work_is_rejected_even_when_both_receipts_agree(self):
        marker = json.loads((self.archive / "raw/guard-build-disabled-producer-marker-state.json").read_bytes())
        marker["didWork"] = True
        marker["state"]["didWork"] = True
        self.rewrite("guard-build-disabled-producer-marker-state.json", lambda value: value.update(copy.deepcopy(marker)))
        self.rewrite("artifact.json", lambda value: value.update(disabledMarkerResult=marker))
        with self.assertRaisesRegex(ValueError, "Marker state changed"):
            VERIFY.verify(self.archive)

    def test_rehashed_native_success_attribution_is_rejected(self):
        self.rewrite("artifact.json", lambda value: value.update(nativeStatus="PASS"))
        with self.assertRaisesRegex(ValueError, "Recorded build scope"):
            VERIFY.verify(self.archive)

    def test_rehashed_wrong_ui_dependency_is_rejected(self):
        def change(value):
            components = value["configurations"][0]["components"]
            index = components.index("androidx.compose.ui:ui-android:1.13.0-beta02")
            components[index] = "androidx.compose.ui:ui-android:1.0.1"
        self.rewrite("guard-build-resolved-artifacts.json", change)
        with self.assertRaisesRegex(ValueError, "Compose UI version"):
            VERIFY.verify(self.archive)


if __name__ == "__main__":
    unittest.main()
