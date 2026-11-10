import subprocess
from pathlib import Path
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[2] / "androidApp/src/androidTest/java/dev/logisim/mobile/input/NativeReadinessDiagnostics.java"
PROBE = r"""
import dev.logisim.mobile.input.NativeReadinessDiagnostics;

public final class ReadinessProbe {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] arguments) {
        String scenario = arguments[0];
        if (scenario.equals("bounded")) {
            NativeReadinessDiagnostics.Trace<String> trace = new NativeReadinessDiagnostics.Trace<>(2);
            trace.record("first");
            trace.record("second");
            trace.record("third");
            NativeReadinessDiagnostics.Snapshot<String> snapshot = trace.snapshot();
            require(snapshot.capacity == 2 && snapshot.totalRecords == 3 && snapshot.droppedRecords == 1,
                "Trace must report capacity, complete count and omitted count");
            require(snapshot.records.size() == 2 && snapshot.records.get(0).sequence == 2 &&
                snapshot.records.get(0).value.equals("second") && snapshot.records.get(1).sequence == 3 &&
                snapshot.records.get(1).value.equals("third"), "Trace must retain the ordered tail");
        } else if (scenario.equals("snapshot")) {
            NativeReadinessDiagnostics.Trace<String> trace = new NativeReadinessDiagnostics.Trace<>(1);
            trace.record("first");
            NativeReadinessDiagnostics.Snapshot<String> before = trace.snapshot();
            trace.record("second");
            require(before.totalRecords == 1 && before.droppedRecords == 0 &&
                before.records.get(0).value.equals("first"), "Later records must not rewrite an old snapshot");
            try {
                before.records.clear();
                throw new AssertionError("Snapshot entries must not be mutable");
            } catch (UnsupportedOperationException expected) {}
        } else if (scenario.equals("invalid-capacity")) {
            for (int capacity : new int[]{0, -1}) {
                try {
                    new NativeReadinessDiagnostics.Trace<String>(capacity);
                    throw new AssertionError("Nonpositive capacity was accepted");
                } catch (IllegalArgumentException expected) {}
            }
        } else if (scenario.equals("not-evaluated")) {
            NativeReadinessDiagnostics.WaitState state = new NativeReadinessDiagnostics.WaitState();
            require(state.outcome() == NativeReadinessDiagnostics.WaitOutcome.NOT_EVALUATED &&
                state.attempts() == 0 && state.historicalException() == null,
                "A wait cannot claim an evaluated state before its first predicate");
        } else if (scenario.equals("false-after-error")) {
            NativeReadinessDiagnostics.WaitState state = new NativeReadinessDiagnostics.WaitState();
            state.threw("not focused", 10);
            state.returned(false, 20);
            require(state.outcome() == NativeReadinessDiagnostics.WaitOutcome.RETURNED_FALSE &&
                state.attempts() == 2 && state.lastOutcomeAtMillis() == 20,
                "A later false predicate must replace the current exception outcome");
            require(state.historicalException().equals("not focused") && state.historicalExceptionAttempt() == 1 &&
                state.historicalExceptionAtMillis() == 10, "The previous exception must remain explicitly historical");
        } else if (scenario.equals("ready-after-error")) {
            NativeReadinessDiagnostics.WaitState state = new NativeReadinessDiagnostics.WaitState();
            state.threw("not focused", 10);
            state.returned(true, 20);
            require(state.outcome() == NativeReadinessDiagnostics.WaitOutcome.READY && state.attempts() == 2 &&
                state.lastOutcomeAtMillis() == 20 && state.historicalExceptionAttempt() == 1,
                "Readiness must be the latest result, with the previous exception separately identified");
        } else if (scenario.equals("latest-error")) {
            NativeReadinessDiagnostics.WaitState state = new NativeReadinessDiagnostics.WaitState();
            state.threw("old error", 10);
            state.returned(false, 20);
            state.threw("new error", 30);
            require(state.outcome() == NativeReadinessDiagnostics.WaitOutcome.THREW && state.attempts() == 3 &&
                state.lastOutcomeAtMillis() == 30 && state.historicalException().equals("new error") &&
                state.historicalExceptionAttempt() == 3 && state.historicalExceptionAtMillis() == 30,
                "The latest exception must identify its actual attempt and time");
        } else throw new AssertionError("Unknown scenario " + scenario);
        System.out.println("PASS " + scenario);
    }
}
"""


class NativeReadinessDiagnosticsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.classes = Path(cls.directory.name)
        probe = cls.classes / "ReadinessProbe.java"
        probe.write_text(PROBE)
        subprocess.run(["javac", "--release", "8", "-d", str(cls.classes), str(SOURCE), str(probe)],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def prove(self, scenario):
        result = subprocess.run(["java", "-cp", str(self.classes), "ReadinessProbe", scenario],
                                check=True, capture_output=True, text=True)
        self.assertEqual("PASS " + scenario, result.stdout.strip())

    def test_trace_retains_an_ordered_bounded_tail_with_explicit_omissions(self):
        self.prove("bounded")

    def test_snapshot_is_detached_and_immutable(self):
        self.prove("snapshot")

    def test_capacity_must_be_positive(self):
        self.prove("invalid-capacity")

    def test_initial_wait_has_no_claimed_predicate_result(self):
        self.prove("not-evaluated")

    def test_last_false_is_distinct_from_a_historical_exception(self):
        self.prove("false-after-error")

    def test_ready_after_an_exception_keeps_the_exception_historical(self):
        self.prove("ready-after-error")

    def test_latest_exception_keeps_its_attempt_and_monotonic_time(self):
        self.prove("latest-error")


if __name__ == "__main__":
    unittest.main()
