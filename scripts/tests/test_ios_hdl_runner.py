import copy
import importlib.util
from pathlib import Path
import unittest
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ios_hdl_runner", ROOT / "scripts/run-ios-hdl.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def completed_events():
    return [
        {"operation": "yosys-import", "error_code": "synthesis-failed"},
        {"operation": "yosys-import", "result": {"format": "yosys-bit-netlist-v1"}},
        {"operation": "yosys-import", "result": {"format": "yosys-bit-netlist-v1"}},
        {"operation": "hdl.start", "result": {"ok": True, "timeNs": 0}},
        {"operation": "hdl.step", "result": {"ok": True, "timeNs": 5, "outputs": {"y": {"logic": "01"}}}},
        {"operation": "hdl.step", "result": {"ok": True, "timeNs": 7, "outputs": {"y": {"logic": "11"}}}},
        {"operation": "hdl.reset", "result": {"ok": True, "timeNs": 0}},
        {"operation": "hdl.step", "result": {"ok": True, "timeNs": 9, "outputs": {"y": {"logic": "01"}}}},
        {"operation": "hdl.cancel", "result": {"ok": True}},
        {"operation": "hdl.start", "result": {"ok": False, "code": "analysis_failed"}},
    ]


class HdlAuditTests(unittest.TestCase):
    def test_accepts_real_operation_shapes_and_global_engine_times(self):
        result = runner.verify_audit(completed_events())
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["samples"], [(5, "01"), (7, "11"), (9, "01")])

    def test_requires_both_native_tests_without_skips(self):
        runner.verify_summary({"totalTestCount": 2, "passedTests": 2, "failedTests": 0, "skippedTests": 0})
        for summary in [
            {"totalTestCount": 0, "passedTests": 0, "failedTests": 0, "skippedTests": 0},
            {"totalTestCount": 2, "passedTests": 1, "failedTests": 0, "skippedTests": 1},
        ]:
            with self.subTest(summary=summary):
                with self.assertRaises(ValueError):
                    runner.verify_summary(summary)

    def test_debug_summary_must_match_selected_count(self):
        runner.verify_summary({"totalTestCount": 1, "passedTests": 1, "failedTests": 0, "skippedTests": 0}, 1)
        with self.assertRaises(ValueError):
            runner.verify_summary({"totalTestCount": 1, "passedTests": 1, "failedTests": 0, "skippedTests": 0})

    def test_partial_selection_cannot_run_without_alpha(self):
        for scenario in ["testCompactCosimulationSamplesResetStopAndCompilerFailure",
                         "testCompactKeyboardActionPreservesExistingHdlSource"]:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary) / "must-not-be-created"
                result = subprocess.run([sys.executable, str(ROOT / "scripts/run-ios-hdl.py"),
                                         "--only-testing", scenario,
                                         "--simulator", "20A4D87D-A2A6-46BD-A889-C4888CE1E152",
                                         "--baseline-sha", "fc5f8769", "--output", str(output)],
                                        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.assertEqual(result.returncode, 2)
                self.assertIn("Partial scenario selection requires --alpha", result.stderr)
                self.assertFalse(output.exists())

    def test_refuses_missing_import_or_session_operations(self):
        for operation in ["yosys-import", "hdl.start", "hdl.step", "hdl.reset", "hdl.cancel"]:
            with self.subTest(operation=operation):
                with self.assertRaises(ValueError):
                    runner.verify_audit([event for event in completed_events() if event["operation"] != operation])

    def test_refuses_unknown_or_wrong_compiler_outputs(self):
        for value in ["??", "00", "1", "011"]:
            with self.subTest(value=value):
                events = copy.deepcopy(completed_events())
                events[4]["result"]["outputs"]["y"]["logic"] = value
                with self.assertRaises(ValueError):
                    runner.verify_audit(events)

    def test_refuses_reset_that_did_not_return_to_zero(self):
        events = completed_events()
        events[6]["result"]["timeNs"] = 8
        with self.assertRaises(ValueError):
            runner.verify_audit(events)

    def test_refuses_missing_real_yosys_failure(self):
        events = completed_events()
        events[0].pop("error_code")
        with self.assertRaises(ValueError):
            runner.verify_audit(events)

    def test_refuses_missing_real_hdl_compiler_failure(self):
        events = completed_events()
        events[-1]["result"]["ok"] = True
        with self.assertRaises(ValueError):
            runner.verify_audit(events)


if __name__ == "__main__":
    unittest.main()
