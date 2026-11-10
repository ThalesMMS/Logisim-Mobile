#!/usr/bin/env python3
"""Exercise the non-suppressing observer boundary without a device or reader."""

import base64
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from android_ui import AndroidUi, IncompleteSnapshotTree, SnapshotRootUnavailable
from test_android_focus_snapshot import FakeClock, incomplete_result, unavailable_root_result


PACKAGE = "dev.logisim.mobile.e2e"


class ReaderObserverUi(AndroidUi):
    def __init__(self, output, **options):
        super().__init__("/fixture/adb", "fixture-device", PACKAGE, output, **options)
        self.calls = []
        self.instrumentation_calls = []
        self.foreground = PACKAGE
        self.pid = "123"
        self.pids = []
        self.responses = []
        self.mutate = lambda fields: fields
        self.code = -1
        self.returncode = 0
        self.stderr = ""
        self.suffix = ""
        self.focused = False
        self.clock = None
        self.capture_seconds = 0
        self.timeout_error = None

    def _run(self, args, *, text=True, **options):
        args = tuple(args)
        self.calls.append(args)
        if args[:3] == ("shell", "dumpsys", "activity"):
            stdout = ("topResumedActivity=ActivityRecord{abc u0 " + self.foreground
                      + "/ExampleActivity t2}") if self.foreground else "No resumed activity"
        elif args[:2] == ("shell", "pidof"):
            stdout = self.pids.pop(0) if self.pids else self.pid
        elif args[:3] == ("shell", "am", "instrument"):
            nonce = args[7]
            self.instrumentation_calls.append({"nonce": nonce, "timeout": options["timeout"], "target": args[-1]})
            if self.clock:
                self.clock.advance(min(self.capture_seconds, options["timeout"]))
            if self.timeout_error:
                raise self.timeout_error
            xml = (f'<hierarchy rotation="0" snapshot-nonce="{nonce}" uptime-millis="2345">'
                   f'<node package="{PACKAGE}" class="android.widget.Button" text="Reset" '
                   f'focused="{str(self.focused).lower()}" accessibility-focused="true" '
                   'bounds="[10,100][300,150]"/></hierarchy>')
            mutate, code = self.responses.pop(0) if self.responses else (self.mutate, self.code)
            fields = mutate({"snapshotNonce": nonce, "snapshotExpectedPackage": PACKAGE,
                             "snapshotXmlBase64": base64.b64encode(xml.encode()).decode(),
                             "snapshotUptimeMillis": "2345", "snapshotRotation": "0"})
            stdout = "\n".join("INSTRUMENTATION_RESULT: " + key + "=" + value for key, value in fields.items())
            stdout += f"\nINSTRUMENTATION_CODE: {code}\n" + self.suffix
        else:
            raise AssertionError(f"Unexpected device command: {args}")
        instrumentation = args[:3] == ("shell", "am", "instrument")
        return subprocess.CompletedProcess(args, self.returncode if instrumentation else 0,
                                           stdout, self.stderr if instrumentation else "")


class ReaderObserverTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name)
        self.process = patch("subprocess.run", side_effect=AssertionError("Real device processes are forbidden"))
        self.process.start()
        self.addCleanup(self.process.stop)

    def observer(self, name="run", **options):
        return ReaderObserverUi(self.output / name, preserve_accessibility_services=True, **options)

    def assert_no_cli(self, ui):
        self.assertFalse(any("uiautomator" in command for command in ui.calls))

    def receipts(self, ui):
        return [json.loads(path.read_text()) for path in sorted(ui.output.glob("focus-snapshot-*.json"))]

    def test_opt_in_requires_an_isolated_e2e_package(self):
        for package in ("dev.logisim.mobile", "dev.logisim.mobile.e2e.other"):
            with self.subTest(package=package), self.assertRaisesRegex(ValueError, "isolated .e2e"):
                AndroidUi("/fixture/adb", "fixture-device", package, self.output / package,
                          preserve_accessibility_services=True)

    def test_default_and_explicit_false_keep_the_original_cli_route(self):
        for options in ({}, {"preserve_accessibility_services": False}):
            with self.subTest(options=options):
                ui = ReaderObserverUi(self.output / str(len(options)), **options)
                with patch.object(ui, "_screen_uiautomator", return_value="original-cli") as cli:
                    self.assertEqual("original-cli", ui.screen(timeout=17))
                cli.assert_called_once_with(17)
                self.assertEqual([], ui.calls)

    def test_unfocused_buttons_always_use_the_sdk_on_successive_observations(self):
        for focus_snapshots in (False, True):
            with self.subTest(focus_snapshots=focus_snapshots):
                ui = self.observer(str(focus_snapshots), focus_snapshots=focus_snapshots)
                for _ in range(2):
                    root = ui.screen()
                    node = root.find("node")
                    self.assertEqual("Reset", node.get("text"))
                    self.assertEqual("false", node.get("focused"))
                    self.assertEqual("true", node.get("accessibility-focused"))
                    self.assertFalse(ui._focused_snapshot_active)
                self.assertEqual(2, len(ui.instrumentation_calls))
                self.assertTrue(all(call["target"] == ui.snapshot_package
                    + "/dev.logisim.mobile.documents.UiSnapshotInstrumentation" for call in ui.instrumentation_calls))
                self.assertEqual(["complete", "complete"], [r["status"] for r in self.receipts(ui)])
                self.assert_no_cli(ui)

    def test_only_authenticated_unavailable_and_incomplete_results_retry(self):
        ui = self.observer()
        ui.responses = [(unavailable_root_result, 0), (incomplete_result, 0), (lambda fields: fields, -1)]
        self.assertEqual("Reset", ui.screen().find("node").get("text"))
        calls = ui.instrumentation_calls
        self.assertEqual(3, len(calls))
        self.assertEqual(3, len({call["nonce"] for call in calls}))
        receipts = self.receipts(ui)
        self.assertEqual(["root-unavailable", "incomplete-tree", "complete"], [r["status"] for r in receipts])
        self.assertEqual([None, "123", "123"], [r["expectedScreenPid"] for r in receipts])
        self.assertTrue(receipts[0]["validatedRootUnavailable"])
        self.assertTrue(receipts[1]["validatedIncompleteTree"])
        self.assertEqual(1, len(list(ui.output.glob("*.xml"))))
        self.assert_no_cli(ui)

    def test_retries_share_one_absolute_45_second_deadline_and_eight_second_calls(self):
        ui = self.observer()
        ui.clock = FakeClock()
        ui.capture_seconds = 8
        ui.mutate = unavailable_root_result
        ui.code = 0
        with patch("android_ui.time.monotonic", side_effect=ui.clock.monotonic):
            with self.assertRaisesRegex(AssertionError, "existing hierarchy deadline"):
                ui.screen()
        self.assertEqual(45, ui.clock.now)
        self.assertEqual([8, 8, 8, 8, 8, 5], [call["timeout"] for call in ui.instrumentation_calls])
        self.assertEqual([], list(ui.output.glob("*.xml")))
        self.assert_no_cli(ui)

    def test_a_shorter_caller_deadline_is_not_extended(self):
        ui = self.observer()
        ui.clock = FakeClock()
        ui.capture_seconds = 8
        ui.mutate = incomplete_result
        ui.code = 0
        with patch("android_ui.time.monotonic", side_effect=ui.clock.monotonic):
            with self.assertRaisesRegex(AssertionError, "existing hierarchy deadline"):
                ui.screen(timeout=11)
        self.assertEqual(11, ui.clock.now)
        self.assertEqual([8, 3], [call["timeout"] for call in ui.instrumentation_calls])
        self.assert_no_cli(ui)

    def test_foreground_external_or_missing_fails_without_a_cli_observation(self):
        for foreground in ("com.google.android.documentsui", ""):
            with self.subTest(foreground=foreground):
                ui = self.observer(foreground or "missing")
                ui.foreground = foreground
                with self.assertRaisesRegex(AssertionError, "foreground"):
                    ui.screen()
                self.assertEqual([], ui.instrumentation_calls)
                self.assert_no_cli(ui)

    def test_foreground_change_during_either_retry_kind_is_terminal(self):
        for kind in (unavailable_root_result, incomplete_result):
            with self.subTest(kind=kind.__name__):
                ui = self.observer(kind.__name__)
                def before_external(fields):
                    ui.foreground = "com.google.android.documentsui"
                    return kind(fields)
                ui.responses = [(before_external, 0)]
                with self.assertRaisesRegex(AssertionError, "foreground"):
                    ui.screen()
                self.assertEqual(1, len(ui.instrumentation_calls))
                self.assert_no_cli(ui)

    def test_pid_change_during_capture_or_before_a_retry_is_terminal(self):
        for name, pids, responses in (
            ("during", ["123", "456"], []),
            ("retry", ["123", "123", "456"], [(unavailable_root_result, 0)]),
        ):
            with self.subTest(name=name):
                ui = self.observer(name)
                ui.pids, ui.responses = pids, responses
                with self.assertRaisesRegex(AssertionError, "app process"):
                    ui.screen()
                self.assertEqual(1, len(ui.instrumentation_calls))
                self.assertEqual([], list(ui.output.glob("*.xml")))
                self.assert_no_cli(ui)

    def test_malformed_or_unauthenticated_retry_results_are_terminal(self):
        changes = (
            {"snapshotNonce": "old"},
            {"snapshotExpectedPackage": "another.app.e2e"},
            {"snapshotXmlBase64": "PHBhcnRpYWwvPg=="},
            {"snapshotCaptureElapsedMillis": "999"},
            {"snapshotCaptureAttemptRecordsBase64": "not-base64"},
        )
        for kind in (unavailable_root_result, incomplete_result):
            for index, mutation in enumerate(changes):
                with self.subTest(kind=kind.__name__, mutation=mutation):
                    ui = self.observer(kind.__name__ + str(index))
                    ui.mutate = lambda fields: dict(kind(fields), **mutation)
                    ui.code = 0
                    with self.assertRaises(AssertionError) as rejected:
                        ui.screen()
                    self.assertNotIsInstance(rejected.exception, (IncompleteSnapshotTree, SnapshotRootUnavailable))
                    self.assertEqual(1, len(ui.instrumentation_calls))
                    self.assertEqual("rejected", self.receipts(ui)[0]["status"])
                    self.assertEqual([], list(ui.output.glob("*.xml")))
                    self.assert_no_cli(ui)

    def test_generic_error_and_invalid_success_metadata_do_not_retry(self):
        for name, mutation, code in (
            ("generic", lambda fields: dict(fields, snapshotError="IllegalStateException: no root"), 0),
            ("xml", lambda fields: dict(fields, snapshotXmlBase64="not-base64"), -1),
            ("rotation", lambda fields: dict(fields, snapshotRotation="4"), -1),
            ("uptime", lambda fields: dict(fields, snapshotUptimeMillis="2344"), -1),
        ):
            with self.subTest(name=name):
                ui = self.observer(name)
                ui.mutate, ui.code = mutation, code
                with self.assertRaises(AssertionError):
                    ui.screen()
                self.assertEqual(1, len(ui.instrumentation_calls))
                self.assert_no_cli(ui)

    def test_fatal_protocol_is_not_treated_as_a_retryable_state(self):
        for name, suffix, stderr in (
            ("after-terminal", "INSTRUMENTATION_RESULT: late=value\n", ""),
            ("second-terminal", "INSTRUMENTATION_CODE: 0\n", ""),
            ("aborted", "", "INSTRUMENTATION_ABORTED: Process crashed.\n"),
        ):
            with self.subTest(name=name):
                ui = self.observer(name)
                ui.mutate, ui.code = unavailable_root_result, 0
                ui.suffix, ui.stderr = suffix, stderr
                with self.assertRaises(AssertionError):
                    ui.screen()
                self.assertEqual(1, len(ui.instrumentation_calls))
                self.assert_no_cli(ui)

    def test_adb_failure_and_timeout_are_terminal_without_cli_fallback(self):
        for name in ("exit", "timeout"):
            with self.subTest(name=name):
                ui = self.observer(name)
                if name == "exit":
                    ui.returncode, expected = 1, RuntimeError
                else:
                    ui.timeout_error = subprocess.TimeoutExpired(["fixture"], 8, output="partial")
                    expected = subprocess.TimeoutExpired
                with self.assertRaises(expected):
                    ui.screen()
                self.assertEqual(1, len(ui.instrumentation_calls))
                self.assertEqual([], list(ui.output.glob("*.xml")))
                self.assert_no_cli(ui)


class ReaderObserverInputIsolationTests(unittest.TestCase):
    def test_noninteractive_command_cannot_consume_the_recorders_next_input_line(self):
        recorder = r'''
import json
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, sys.argv[1])
import android_ui

def read_line():
    line = b""
    while not line.endswith(b"\n"):
        part = os.read(0, 1)
        if not part:
            break
        line += part
    return line.decode()

assert read_line() == "observe\n"
fake_adb = Path(sys.argv[2])
original_run = subprocess.run
def only_local_fake(command, **options):
    assert command == [sys.executable, "-s", str(fake_adb), "shell", "fixture"], command
    return original_run(command, **options)
android_ui.subprocess.run = only_local_fake
ui = android_ui.AndroidUi(sys.executable, str(fake_adb), "dev.logisim.mobile.e2e", sys.argv[3],
                          preserve_accessibility_services=sys.argv[4] == "True")
observation = json.loads(ui.adb("shell", "fixture", timeout=3))
print(json.dumps({"command_stdin": observation["stdin"], "parent_next_line": read_line()}))
'''
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            fake_adb = directory / "fake_adb.py"
            fake_adb.write_text('import json, sys\nassert sys.argv[1:] == ["shell", "fixture"]\n'
                                'print(json.dumps({"stdin": sys.stdin.read()}))\n')
            pending_line = '{"op":"after-observation"}\n'
            for preserve in (False, True):
                with self.subTest(preserve_accessibility_services=preserve):
                    result = subprocess.run(
                        [sys.executable, "-c", recorder, str(Path(__file__).resolve().parent),
                         str(fake_adb), str(directory / str(preserve)), str(preserve)],
                        input="observe\n" + pending_line, capture_output=True, text=True, timeout=5,
                    )
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual({"command_stdin": "", "parent_next_line": pending_line},
                                     json.loads(result.stdout))


if __name__ == "__main__":
    unittest.main()
