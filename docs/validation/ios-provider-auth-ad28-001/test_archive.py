#!/usr/bin/env python3
"""Exercise the standalone verifier; no archived runner is imported or executed."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
from verify import verify

ROOT = Path(__file__).resolve().parent


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def refresh(folder):
    manifest_path = folder / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    for entry in manifest["files"]:
        raw = (folder / entry["path"]).read_bytes()
        entry.update(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
    write_json(manifest_path, manifest)
    lines = []
    for path in sorted(folder.rglob("*")):
        if path.is_file() and path != folder / "SHA256SUMS":
            lines.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.relative_to(folder).as_posix())
    (folder / "SHA256SUMS").write_text("\n".join(lines) + "\n")


class ArchiveChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="logisim-auth-preflight-archive-")
        self.folder = Path(self.temporary.name) / "relocated"
        shutil.copytree(ROOT, self.folder)

    def tearDown(self):
        self.temporary.cleanup()

    def change_json(self, relative, change):
        path = self.folder / relative
        data = json.loads(path.read_text())
        change(data)
        write_json(path, data)

    def reject(self, text, rehash=True):
        if rehash:
            refresh(self.folder)
        with self.assertRaisesRegex(ValueError, text):
            verify(self.folder)

    def test_original(self):
        self.assertEqual(verify(self.folder)["originalOutcome"], "FAIL")

    def test_relocated_standalone(self):
        result = subprocess.run([sys.executable, str(self.folder / "verify.py")], cwd=self.temporary.name,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["testExecution"], "NATIVE_NOT_RUN")

    def test_raw_corruption(self):
        path = self.folder / "evidence/run/008-installed-applications.stdout"
        path.write_bytes(path.read_bytes() + b" ")
        self.reject("Changed archive file", rehash=False)

    def test_unlisted_nested_checksum_file(self):
        (self.folder / "evidence/SHA256SUMS").write_text("unlisted evidence\n")
        self.reject("Absent or unlisted archive file", rehash=False)

    def test_cannot_promote_failure(self):
        self.change_json("evidence/run/results.json", lambda data: data.update(status="PASS"))
        self.reject("failed outcome must remain FAIL")

    def test_install_command_is_not_admissible(self):
        self.change_json("evidence/run/commands.json", lambda data: data[8].update(arguments=["xcrun", "simctl", "install", "AD28", "Main.app"]))
        self.reject("Only retained preparation/inventory commands")

    def test_conversion_cannot_invent_main(self):
        self.change_json("evidence/run/offline-listapps-conversion.stdout.json", lambda data: data.update({"thalesmms.logisim.mobile": {}}))
        self.reject("OpenStep conversion or Main absence changed")

    def test_postflight_identity_cannot_drift(self):
        self.change_json("evidence/run/filesystem-postflight.json", lambda data: data["artifacts"]["installedFixture"].update(bundle_tree_sha256="0" * 64))
        self.change_json("receipt.json", lambda data: data["postflightIdentities"]["installedFixture"].update(bundle_tree_sha256="0" * 64))
        self.reject("Captured artifact identity changed")

    def test_lease_cannot_change(self):
        self.change_json("evidence/run/RootLease.json", lambda data: data.update(nativeAuthorized=False))
        self.reject("Captured lease or separate historical review flag changed")

    def test_pinned_source_cannot_change(self):
        path = self.folder / "evidence/pinned-source/scripts/run-ios-provider-auth.py"
        path.write_bytes(path.read_bytes() + b"\n")
        self.reject("Pinned source bytes changed")


if __name__ == "__main__":
    unittest.main(verbosity=2)
