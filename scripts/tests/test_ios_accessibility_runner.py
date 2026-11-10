import importlib.util
import json
from pathlib import Path
import plistlib
import signal
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("ios_accessibility_runner", Path(__file__).parents[1] / "run-ios-accessibility.py")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
SIMULATOR = "20A4D87D-A2A6-46BD-A889-C4888CE1E152"


class IosAccessibilityRunnerTest(unittest.TestCase):
    def execute(self, *, test_exit=0, build_error=False, restore_wrong=False, cleanup_signal=None, cleanup_interrupt=False, missing_resource=False, reuse_cache=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        output = Path(directory.name)
        settings = {"content_size": "medium", "increase_contrast": "disabled"}
        initial = settings.copy()
        commands = []
        mutation_seen = False

        def command(arguments, *, log=None):
            nonlocal mutation_seen
            commands.append(arguments)
            if arguments[:4] == ["xcrun", "simctl", "list", "devices"]:
                return json.dumps({"devices": {"iOS-27": [{"udid": SIMULATOR, "state": "Booted", "name": "iPhone 17e"}]}})
            if arguments[:3] == ["xcrun", "simctl", "ui"]:
                key = arguments[4]
                if len(arguments) == 6:
                    mutation_seen = True
                    if key == "content_size" and arguments[5] == initial[key]:
                        if cleanup_signal is not None:
                            signal.getsignal(cleanup_signal)(cleanup_signal, None)
                        if cleanup_interrupt:
                            raise KeyboardInterrupt("Interrupted restoration command")
                    if not (restore_wrong and key == "content_size" and arguments[5] == initial[key]):
                        settings[key] = arguments[5]
                    return ""
                return settings[key]
            if arguments[0] == "xcodebuild":
                if build_error and "build-for-testing" in arguments:
                    raise RuntimeError("Build tool disconnected")
                if "build-for-testing" in arguments and not missing_resource:
                    derived_data = Path(arguments[arguments.index("-derivedDataPath") + 1])
                    resource = derived_data / "Build/Products/Debug-iphonesimulator/AccessibilityUITests-Runner.app/PlugIns/AccessibilityUITests.xctest/Catalog.json"
                    resource.parent.mkdir(parents=True, exist_ok=True)
                    resource.write_text("{}")
                return test_exit if "test-without-building" in arguments else 0
            if arguments[:3] == ["xcrun", "simctl", "install"]:
                return ""
            raise AssertionError(f"Unexpected command: {arguments}")

        argv = ["runner", "--simulator", SIMULATOR, "--bundle-id", "thalesmms.logisim.mobile",
                "--baseline-sha", "123abcd", "--app", str(output / "App.app"), "--output", str(output)]
        if reuse_cache:
            argv += ["--derived-data", str(output / "shared harness cache")]
        with patch.object(sys, "argv", argv), patch.object(runner, "prepare", return_value=output / "Harness.xcodeproj"), \
                patch.object(runner, "artifact", return_value={"bundle_id": "thalesmms.logisim.mobile"}), \
                patch.object(runner, "run", side_effect=command):
            code = runner.main()
        return code, json.loads((output / "results.json").read_text()), settings, initial, commands, mutation_seen

    def test_success_runs_both_sizes_and_restores_exact_settings(self):
        code, report, settings, initial, commands, _ = self.execute()
        self.assertEqual(0, code)
        self.assertEqual(initial, settings)
        self.assertEqual("PASS", report["status"])
        self.assertEqual(["large", "accessibility-extra-extra-extra-large"], [case["content_size"] for case in report["cases"]])
        self.assertEqual("NOT RUN", report["voiceover"]["status"])
        self.assertFalse(report["physical_device"])
        self.assertTrue(all(result["status"] == "PASS" for result in report["restoration"].values()))
        self.assertEqual(2, sum("test-without-building" in arguments for arguments in commands))

    def test_test_failure_still_restores_and_returns_failure(self):
        code, report, settings, initial, _, _ = self.execute(test_exit=65)
        self.assertEqual(1, code)
        self.assertEqual(initial, settings)
        self.assertEqual("FAIL", report["status"])
        self.assertEqual([65, 65], [case["exit_code"] for case in report["cases"]])

    def test_unexpected_build_failure_is_recorded_and_restored(self):
        code, report, settings, initial, _, _ = self.execute(build_error=True)
        self.assertEqual(1, code)
        self.assertEqual(initial, settings)
        self.assertEqual("Build tool disconnected", report["execution_error"]["message"])
        self.assertEqual([], report["cases"])

    def test_restoration_failure_cannot_produce_a_successful_exit(self):
        code, report, settings, initial, _, _ = self.execute(restore_wrong=True)
        self.assertEqual(1, code)
        self.assertNotEqual(initial, settings)
        self.assertEqual("FAIL", report["restoration"]["content_size"]["status"])
        self.assertEqual("FAIL", report["status"])

    def test_bundle_fingerprint_includes_the_shared_framework(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / "App.app"
            framework = app / "Frameworks/ComposeApp.framework/ComposeApp"
            framework.parent.mkdir(parents=True)
            (app / "App").write_bytes(b"same wrapper")
            with (app / "Info.plist").open("wb") as stream:
                plistlib.dump({"CFBundleIdentifier": "thalesmms.logisim.mobile", "CFBundleExecutable": "App"}, stream)
            framework.write_bytes(b"old shared code")
            before = runner.artifact(app, "thalesmms.logisim.mobile")
            framework.write_bytes(b"new shared code")
            after = runner.artifact(app, "thalesmms.logisim.mobile")
            self.assertEqual(before["executable_sha256"], after["executable_sha256"])
            self.assertNotEqual(before["bundle_tree_sha256"], after["bundle_tree_sha256"])

    def test_cleanup_defers_signals_and_restores_every_setting(self):
        for incoming in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=incoming):
                code, report, settings, initial, _, _ = self.execute(cleanup_signal=incoming)
                self.assertEqual(130, code)
                self.assertEqual(initial, settings)
                self.assertEqual([incoming], report["deferred_cleanup_signals"])
                self.assertTrue(all(result["status"] == "PASS" for result in report["restoration"].values()))

    def test_interrupted_restore_command_does_not_skip_the_next_setting(self):
        code, report, settings, initial, _, _ = self.execute(cleanup_interrupt=True)
        self.assertEqual(1, code)
        self.assertEqual("FAIL", report["restoration"]["content_size"]["status"])
        self.assertEqual("PASS", report["restoration"]["increase_contrast"]["status"])
        self.assertEqual(initial["increase_contrast"], settings["increase_contrast"])

    def test_bundle_mismatch_is_rejected_before_device_actions(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            with (app / "Info.plist").open("wb") as stream:
                plistlib.dump({"CFBundleIdentifier": "other.app"}, stream)
            with self.assertRaisesRegex(ValueError, "bundle identifier"):
                runner.artifact(app, "thalesmms.logisim.mobile")

    def test_missing_test_resource_blocks_native_execution(self):
        code, report, settings, initial, commands, _ = self.execute(missing_resource=True)
        self.assertEqual(1, code)
        self.assertEqual(initial, settings)
        self.assertEqual("NATIVE_NOT_RUN", report["native_execution_gate"])
        self.assertIn("missing Catalog.json", report["execution_error"]["message"])
        self.assertFalse(any("test-without-building" in arguments for arguments in commands))

    def test_explicit_build_cache_is_used_for_build_and_resource_preflight(self):
        code, report, _, _, commands, _ = self.execute(reuse_cache=True)
        self.assertEqual(0, code)
        self.assertTrue(report["derived_data"].endswith("shared harness cache"))
        self.assertTrue(all(arguments[arguments.index("-derivedDataPath") + 1] == report["derived_data"]
                            for arguments in commands if arguments[0] == "xcodebuild"))


if __name__ == "__main__":
    unittest.main()
