"""Selection and receipt preflight only: no native actions, AndroidUi or ADB."""
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from android_io_showcase_scenarios import IO_CASE_NAMES, normalize_io_cases, run_io_showcase


class IoCaseSelectionTest(unittest.TestCase):
    def test_omitting_selection_keeps_the_original_five_case_order(self):
        self.assertEqual(("signals", "dip-last", "pointer-controls", "keyboard-tty", "dimensions"), normalize_io_cases())

    def test_explicit_selection_preserves_requested_order(self):
        self.assertEqual(("keyboard-tty", "pointer-controls"), normalize_io_cases(["keyboard-tty", "pointer-controls"]))

    def test_empty_unknown_and_duplicate_selections_fail_before_importing_the_harness(self):
        for values in [[], [""], ["unknown"], ["signals", "signals"], "signals", [" signals"], [None]]:
            with self.subTest(values=values), patch.dict(sys.modules, {"android_editing_scenarios": None}):
                with self.assertRaises(ValueError):
                    run_io_showcase(object(), selected_cases=values)

    def run_plan(self, selected=None, failure=None):
        # The host records scheduled callbacks without executing them. This proves
        # dispatch and receipt scope only, not any of the native I/O case actions.
        module = types.ModuleType("android_editing_scenarios")
        class Editing:
            def __init__(self, host):
                self.s, self.ui = host, host.ui
        module._Editing = Editing
        module._bounds = lambda node: None
        with tempfile.TemporaryDirectory() as directory:
            host = types.SimpleNamespace(ui=types.SimpleNamespace(output=Path(directory), package="synthetic-selection-only", serial="no-device"))
            calls = []
            def run(name, action):
                calls.append((name, action.__name__))
                if name == failure:
                    raise RuntimeError("synthetic dispatch failure")
            host.run = run
            with patch.dict(sys.modules, {"android_editing_scenarios": module}):
                if failure:
                    with self.assertRaisesRegex(RuntimeError, "synthetic dispatch"):
                        run_io_showcase(host, selected_cases=selected)
                else:
                    run_io_showcase(host, selected_cases=selected)
            return calls, json.loads((Path(directory) / "io-showcase-observations.json").read_text())

    def test_selected_plan_does_not_schedule_signals_or_dip_and_reports_partial_scope(self):
        calls, report = self.run_plan(["pointer-controls", "keyboard-tty"])
        self.assertEqual([("io-showcase-pointer-controls", "pointers"), ("io-showcase-keyboard-tty", "text")], calls)
        self.assertEqual("selected-cases", report["selectionScope"])
        self.assertEqual(["pointer-controls", "keyboard-tty"], report["selectedCases"])
        self.assertEqual(["signals", "dip-last", "dimensions"], report["notSelectedCases"])
        self.assertEqual(["passed", "passed"], [item["status"] for item in report["caseResults"]])
        self.assertEqual("required", report["visualReview"])
        self.assertIn("No overall #44 completion", report["qualification"])

    def test_default_plan_schedules_all_original_scenario_names(self):
        calls, report = self.run_plan()
        self.assertEqual(["io-showcase-" + name for name in IO_CASE_NAMES], [name for name, _ in calls])
        self.assertEqual("all-cases", report["selectionScope"])
        self.assertEqual([], report["notSelectedCases"])

    def test_failure_preserves_the_failed_case_and_unstarted_selected_cases(self):
        calls, report = self.run_plan(["keyboard-tty", "pointer-controls", "dimensions"], failure="io-showcase-pointer-controls")
        self.assertEqual(["io-showcase-keyboard-tty", "io-showcase-pointer-controls"], [name for name, _ in calls])
        self.assertEqual(["passed", "failed", "not_run"], [item["status"] for item in report["caseResults"]])


if __name__ == "__main__":
    unittest.main()
