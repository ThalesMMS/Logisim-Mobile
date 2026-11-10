"""Exercise CLI selection boundaries without contacting Android."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("io_cli_runner", SCRIPTS / "e2e-android.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class BoundaryReached(Exception):
    pass


class IoCliTests(unittest.TestCase):
    def invalid(self, arguments, message):
        error = io.StringIO()
        with patch.object(sys, "argv", ["e2e-android.py", *arguments]), \
                patch.object(RUNNER, "expected_e2e_package") as preflight, \
                patch.object(subprocess, "Popen", side_effect=AssertionError("No process allowed")), \
                contextlib.redirect_stderr(error), self.assertRaises(SystemExit) as exited:
            RUNNER.main()
        self.assertEqual(2, exited.exception.code)
        self.assertIn(message, error.getvalue())
        preflight.assert_not_called()

    def test_wrong_scenario_is_rejected_before_preflight(self):
        self.invalid(["--scenario", "files", "--io-case", "signals"], "requires --scenario io-showcase")

    def test_unknown_case_is_rejected_before_preflight(self):
        self.invalid(["--scenario", "io-showcase", "--io-case", "unknown"], "Unknown I/O cases")

    def test_duplicate_case_is_rejected_before_preflight(self):
        self.invalid(["--scenario", "io-showcase", "--io-case", "signals", "--io-case", "signals"],
                     "only once")

    def test_empty_case_argument_is_rejected(self):
        self.invalid(["--scenario", "io-showcase", "--io-case"], "expected one argument")

    def context_before_device(self, selected, auto_discover):
        import json
        from android_io_showcase_scenarios import IO_CASE_NAMES
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            apks = tuple(base / name for name in ("app.apk", "test.apk", "snapshot.apk"))
            for apk in apks:
                apk.write_bytes(apk.name.encode())
            output = base / "evidence"
            arguments = ["--scenario", "io-showcase", "--serial", "" if auto_discover else "emulator-5556",
                         "--output", str(output)]
            for name in selected or ():
                arguments.extend(["--io-case", name])
            expected = list(IO_CASE_NAMES if selected is None else selected)

            def check_context(*args, **kwargs):
                context = json.loads((output / "manifest.json").read_text())["ioCaseSelection"]
                self.assertEqual(expected, context["selectedCases"])
                self.assertNotIn("caseResults", context)
                self.assertEqual("all-cases" if selected is None else "selected-cases", context["selectionScope"])
                if auto_discover:
                    self.assertEqual("devices", args[0][-1])
                raise BoundaryReached()

            with patch.object(sys, "argv", ["e2e-android.py", *arguments]), \
                    patch.object(RUNNER, "artifact_paths", return_value=apks), \
                    patch.object(RUNNER, "find_aapt2", return_value=base / "aapt2"), \
                    patch.object(RUNNER, "validate_apk_manifests", return_value={}), \
                    patch.object(RUNNER, "AndroidUi", side_effect=check_context), \
                    patch.object(subprocess, "check_output", side_effect=check_context), \
                    patch.object(subprocess, "Popen", side_effect=AssertionError("No process allowed")), \
                    self.assertRaises(BoundaryReached):
                RUNNER.main()

    def test_selected_units_are_recorded_before_ui_construction(self):
        self.context_before_device(["pointer-controls", "keyboard-tty"], False)

    def test_default_full_selection_is_recorded_before_device_discovery(self):
        self.context_before_device(None, True)


if __name__ == "__main__":
    unittest.main()
