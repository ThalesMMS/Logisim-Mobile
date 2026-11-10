"""Verify CI SDK discovery without downloading or modifying an Android SDK."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci-install-android.sh"


class AndroidPackageInstallationTest(unittest.TestCase):
    def run_installer(self, sdk, directory=None, use_legacy_variable=False):
        env = os.environ.copy()
        env.pop("ANDROID_HOME", None)
        env.pop("ANDROID_SDK_ROOT", None)
        if sdk is not None:
            env["ANDROID_SDK_ROOT" if use_legacy_variable else "ANDROID_HOME"] = str(sdk)
        if directory is not None:
            manager = sdk / "cmdline-tools" / directory / "bin" / "sdkmanager"
            manager.parent.mkdir(parents=True)
            manager.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            manager.chmod(0o755)
        return subprocess.run(["/bin/sh", str(SCRIPT)], env=env, capture_output=True, text=True)

    def test_discovers_manager_outside_path_and_preserves_sdk_with_spaces(self):
        with tempfile.TemporaryDirectory(prefix="android sdk ") as temporary:
            sdk = Path(temporary)
            result = self.run_installer(sdk, "latest")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([f"--sdk_root={sdk}", "platforms;android-37.0", "platforms;android-37.1", "build-tools;36.0.0"],
                             result.stdout.splitlines())

    def test_discovers_versioned_tools_from_legacy_sdk_variable(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = self.run_installer(Path(temporary), "16.0", use_legacy_variable=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("platforms;android-37.0", result.stdout)
            self.assertIn("platforms;android-37.1", result.stdout)

    def test_missing_sdk_has_actionable_error(self):
        result = self.run_installer(None)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Set ANDROID_HOME", result.stderr)

    def test_missing_command_line_tools_fails_before_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = self.run_installer(Path(temporary))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("command-line tools are missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
