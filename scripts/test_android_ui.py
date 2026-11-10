#!/usr/bin/env python3
"""Test the adb driver without a device or persistent output files."""

import io
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from android_ui import AndroidUi, UiNodeNotFound


PNG = b"\x89PNG\r\n\x1a\nfixture"
PROJECT = '<project version="1.0"><circuit name="main"/></project>'
UI = '<hierarchy><node text="Open" content-desc="Open a file" resource-id="open" bounds="[0,0][100,40]" enabled="true"/><node text="Other" content-desc="" resource-id="other" bounds="[0,40][100,80]"/></hierarchy>'
EDITOR = '<hierarchy><node class="android.widget.EditText" text="old" focused="true" bounds="[0,0][100,40]"/></hierarchy>'


def checkpoint_archive():
    output = io.BytesIO()
    payload = ("LOGISIM-WORKSPACE\n1\n\n" + PROJECT).encode()
    with tarfile.open(fileobj=output, mode="w") as archive:
        member = tarfile.TarInfo("files/workspace/current.workspace")
        member.size = len(payload)
        archive.addfile(member, io.BytesIO(payload))
    return output.getvalue()


class FakeAndroidUi(AndroidUi):
    def __init__(self, output, package="dev.logisim.mobile.e2e"):
        super().__init__("/fixture/adb", "fixture-serial", package, output)
        self.calls = []
        self.dump_count = 0

    def _run(self, args, *, text=True, **options):
        args = tuple(args)
        self.calls.append(args)
        stdout = ""
        if args[:3] == ("shell", "uiautomator", "dump"):
            self.dump_count += 1
            stdout = "ERROR: null root node" if self.dump_count == 1 else "UI hierarchy dumped to: " + args[-1]
        elif args[:2] == ("shell", "cat"):
            stdout = UI
        elif args[:2] == ("shell", "pidof"):
            stdout = "123\n"
        elif args[:2] == ("shell", "logcat"):
            stdout = "fixture app log\n"
        elif args[:2] == ("exec-out", "screencap"):
            stdout = PNG
        elif args[:2] == ("exec-out", "run-as"):
            stdout = checkpoint_archive()
        if text and isinstance(stdout, bytes):
            stdout = stdout.decode()
        elif not text and isinstance(stdout, str):
            stdout = stdout.encode()
        return subprocess.CompletedProcess(args, 0, stdout, "" if text else b"")


class FocusedAndroidUi(FakeAndroidUi):
    def screen(self, timeout=30):
        return ET.fromstring(EDITOR)


class BrokenHierarchyAndroidUi(FakeAndroidUi):
    def screen(self, timeout=30):
        raise AssertionError("fixture hierarchy unavailable")


class AndroidUiTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        # An accidental real process invocation makes the suite fail instead of touching a device.
        self.process = patch("subprocess.run", side_effect=AssertionError("Real subprocess forbidden in driver tests"))
        self.process.start()
        self.addCleanup(self.process.stop)

    def test_adb_passes_serial_and_arguments_as_a_process_vector(self):
        self.process.stop()
        with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, "ready", "")) as command:
            driver = AndroidUi("/fixture/adb", "serial with spaces", "dev.logisim.mobile", self.output)
            self.assertEqual("ready", driver.adb("shell", "getprop", "ro.build.version.sdk"))
        arguments, options = command.call_args
        self.assertEqual(["/fixture/adb", "-s", "serial with spaces", "shell", "getprop", "ro.build.version.sdk"], arguments[0])
        self.assertNotIn("shell", options)
        self.assertEqual(30, options["timeout"])

    def test_installed_apk_verification_checks_the_device_hash_for_both_packages(self):
        driver = FakeAndroidUi(self.output)
        artifact = self.output / "fixture.apk"
        artifact.write_bytes(b"literal APK fixture")
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        remote = "/data/app/~~device-id/app.id-AbC_123==/base.apk"
        for package in (driver.package, driver.package + ".test"):
            with self.subTest(package=package), patch.object(driver, "adb", side_effect=[
                    "package:" + remote + "\n", digest + "  " + remote + "\n"]) as adb:
                self.assertEqual({"package": package, "path": remote, "sha256": digest},
                                 driver.verify_installed_apk(artifact, package))
                self.assertEqual([("shell", "pm", "path", package), ("shell", "sha256sum", remote)],
                                 [call.args for call in adb.call_args_list])

    def test_installed_apk_verification_rejects_a_stale_or_unverifiable_artifact(self):
        driver = FakeAndroidUi(self.output)
        artifact = self.output / "fixture.apk"
        artifact.write_bytes(b"literal APK fixture")
        remote = "/data/app/~~device-id/app.id-AbC_123==/base.apk"
        for response in ("0" * 64 + "  " + remote, "sha256sum: permission denied"):
            with self.subTest(response=response), patch.object(driver, "adb", side_effect=[
                    "package:" + remote + "\n", response]):
                with self.assertRaises(AssertionError):
                    driver.verify_installed_apk(artifact)

    def test_installed_apk_verification_rejects_split_missing_and_unexpected_paths(self):
        driver = FakeAndroidUi(self.output)
        for response in ("", "package:/data/app/a/base.apk\npackage:/data/app/a/split.apk\n",
                         "package:/data/app/a/base.apk; touch /sdcard/unsafe"):
            with self.subTest(response=response), patch.object(driver, "adb", return_value=response) as adb:
                with self.assertRaises(AssertionError):
                    driver.verify_installed_apk(self.output / "unused.apk")
                self.assertEqual(1, adb.call_count)
        with self.assertRaises(ValueError):
            driver.verify_installed_apk(self.output / "unused.apk", "some.other.application")

    def test_selectors_are_exact_and_can_be_combined(self):
        root = ET.fromstring(UI)
        node = AndroidUi.find(root, text="Open", description="Open a file", resource_id="open")
        self.assertEqual("Open", node.get("text"))
        self.assertEqual(node, AndroidUi.find(root, description="Open a", prefix=True))
        self.assertIsNone(AndroidUi.optional(root, text="Missing"))
        with self.assertRaises(ValueError):
            AndroidUi.find(root)

    def test_optional_does_not_hide_ambiguous_controls(self):
        root = ET.fromstring(UI)
        with self.assertRaisesRegex(AssertionError, "Ambiguous control"):
            AndroidUi.optional(root, description="", prefix=True)
        with self.assertRaises(UiNodeNotFound):
            AndroidUi.find(root, resource_id="op", prefix=True)

    def test_null_root_retry_uses_a_new_remote_file_and_cleans_both(self):
        driver = FakeAndroidUi(self.output)
        with patch("android_ui.time.sleep"):
            self.assertEqual("hierarchy", driver.screen(timeout=2).tag)
        dumps = [args[-1] for args in driver.calls if args[:3] == ("shell", "uiautomator", "dump")]
        reads = [args[-1] for args in driver.calls if args[:2] == ("shell", "cat")]
        removed = [args[-1] for args in driver.calls if args[:3] == ("shell", "rm", "-f")]
        self.assertEqual(2, len(dumps))
        self.assertNotEqual(dumps[0], dumps[1])
        self.assertEqual([dumps[1]], reads)
        self.assertEqual(dumps, removed)

    def test_text_input_quotes_shell_metacharacters_and_preserves_literal_percent_s(self):
        driver = FocusedAndroidUi(self.output)
        driver.input_text(ET.fromstring(EDITOR).find("node"), "a %s;$(bad)'b")
        text_commands = [shlex.split(args[1]) for args in driver.calls if len(args) == 2 and args[0] == "shell"]
        self.assertEqual([["input", "text", "a%s"], ["input", "text", "%"],
                          ["input", "text", "s;$(bad)'b"]], text_commands)
        self.assertIn(("shell", "input", "keyevent", "123"), driver.calls)
        self.assertIn(("shell", "input", "keyevent", "67", "67", "67"), driver.calls)

    def test_text_input_rejects_unicode_multiline_and_non_editable_targets(self):
        driver = FocusedAndroidUi(self.output)
        editor = ET.fromstring(EDITOR).find("node")
        for value in ("árvore", "line\nline"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                driver.input_text(editor, value)
        with self.assertRaises(ValueError):
            driver.input_text(ET.fromstring(UI).find("node"), "text")
        self.assertEqual([], driver.calls)

    def test_ui_mutation_is_restricted_to_the_e2e_application(self):
        driver = FocusedAndroidUi(self.output, "dev.logisim.mobile")
        for action in (lambda: driver.tap(ET.fromstring(UI).find("node")), lambda: driver.key(4),
                       lambda: driver.input_text(ET.fromstring(EDITOR).find("node"), "text")):
            with self.assertRaises(ValueError):
                action()
        self.assertEqual([], driver.calls)

    def test_disabled_and_empty_bounds_do_not_send_a_tap(self):
        driver = FakeAndroidUi(self.output)
        for node in (ET.fromstring('<node enabled="false" bounds="[0,0][10,10]"/>'),
                     ET.fromstring('<node bounds="[0,0][0,0]"/>')):
            with self.assertRaises(AssertionError):
                driver.tap(node)
        self.assertEqual([], driver.calls)

    def test_capture_saves_the_supplied_current_hierarchy(self):
        driver = FakeAndroidUi(self.output)
        result = driver.capture("step-1", ET.fromstring(UI))
        self.assertEqual(PNG, Path(result["screenshot"]).read_bytes())
        self.assertEqual("Open", ET.parse(result["hierarchy"]).getroot().find("node").get("text"))
        self.assertEqual(0, driver.dump_count)

    def test_failure_collectors_preserve_the_error_when_the_hierarchy_fails(self):
        driver = BrokenHierarchyAndroidUi(self.output)
        result = driver.fail_artifacts("failed-step", AssertionError("Expected output 1"))
        self.assertEqual("Expected output 1", result["scenario_error"])
        self.assertIn("hierarchy", result["collection_errors"])
        self.assertTrue(Path(result["artifacts"]["screenshot"]).is_file())
        self.assertEqual("fixture app log\n", Path(result["artifacts"]["logcat"]).read_text())
        self.assertTrue(Path(result["artifacts"]["private_files"]).is_file())
        checkpoints = json.loads((self.output / "failed-step-checkpoint-documents.json").read_text())
        self.assertEqual("files/workspace/current.workspace", checkpoints[0]["archive_member"])
        self.assertEqual(PROJECT, Path(checkpoints[0]["document"]).read_text())
        report = json.loads((self.output / "failed-step-artifacts.json").read_text())
        self.assertEqual("Expected output 1", report["scenario_error"])

    def test_read_only_diagnostics_never_archive_a_normal_application(self):
        driver = BrokenHierarchyAndroidUi(self.output, "dev.logisim.mobile")
        result = driver.fail_artifacts("read-only", RuntimeError("Missing control"))
        self.assertIn("private_files", result["collection_errors"])
        self.assertFalse(any(args[:2] == ("exec-out", "run-as") for args in driver.calls))

    def test_artifact_names_cannot_escape_the_run_directory(self):
        driver = FakeAndroidUi(self.output)
        with self.assertRaises(ValueError):
            driver.capture("../outside", ET.fromstring(UI))
        self.assertFalse((self.output.parent / "outside.png").exists())


if __name__ == "__main__":
    unittest.main()
