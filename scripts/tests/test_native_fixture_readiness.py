import subprocess
from pathlib import Path
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[2] / "androidApp/src/androidTest/java/dev/logisim/mobile/input/NativeFixtureReadiness.java"
PROBE = r"""
import dev.logisim.mobile.input.NativeFixtureReadiness;
import java.util.function.LongSupplier;

public final class FixtureReadinessProbe {
    static NativeFixtureReadiness.Observation view(boolean complete, boolean report, boolean close, boolean label) {
        return new NativeFixtureReadiness.Observation(complete, report, close, label);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void expect(String outcome, NativeFixtureReadiness.Outcome actual) {
        require(outcome.equals(actual.name()), "Expected " + outcome + ", got " + actual);
    }

    public static void main(String[] arguments) {
        NativeFixtureReadiness readiness = new NativeFixtureReadiness();
        final int[] calls = {0};
        final long[] time = {10};
        LongSupplier clock = () -> time[0];
        Runnable close = () -> calls[0]++;
        String scenario = arguments[0];
        if (scenario.equals("sequence")) {
            expect("WAIT", readiness.step(view(true, false, false, false), clock, 1000, close));
            expect("CLOSE_ATTEMPTED", readiness.step(view(true, true, true, false), clock, 1000, close));
            require(calls[0] == 1, "The observed report must invoke Close once");
            expect("WAIT", readiness.step(view(true, true, true, false), clock, 1000, close));
            expect("READY", readiness.step(view(true, false, false, true), clock, 1000, close));
            require(calls[0] == 1, "The outgoing modal must not trigger another Close");
        } else if (scenario.equals("modal-precedence")) {
            expect("CLOSE_ATTEMPTED", readiness.step(view(true, true, true, true), clock, 1000, close));
            expect("WAIT", readiness.step(view(true, true, true, true), clock, 1000, close));
            require(calls[0] == 1, "A label in a modal snapshot must not bypass modal completion");
        } else if (scenario.equals("incomplete")) {
            expect("WAIT", readiness.step(view(false, true, true, true), clock, 1000, close));
            expect("WAIT", readiness.step(view(false, false, false, true), clock, 1000, close));
            require(calls[0] == 0 && !readiness.closeAttempted(), "A partial tree must neither close nor qualify");
        } else if (scenario.equals("unresolved")) {
            expect("WAIT", readiness.step(view(true, true, false, true), clock, 1000, close));
            require(calls[0] == 0 && !readiness.closeAttempted(), "An unresolved Close cannot emit input");
            expect("CLOSE_ATTEMPTED", readiness.step(view(true, true, true, false), clock, 1000, close));
            require(calls[0] == 1, "A later resolved snapshot must permit the first Close");
        } else if (scenario.equals("expired")) {
            time[0] = 1000;
            expect("EXPIRED", readiness.step(view(true, true, true, true), clock, 1000, close));
            expect("EXPIRED", readiness.step(view(true, false, false, true), clock, 1000, close));
            require(calls[0] == 0 && !readiness.closeAttempted(), "Expiration cannot emit input or qualify readiness");
        } else if (scenario.equals("expires-before-close")) {
            final int[] reads = {0};
            LongSupplier advancingClock = () -> ++reads[0] == 1 ? 999 : 1000;
            expect("EXPIRED", readiness.step(view(true, true, true, false), advancingClock, 1000, close));
            require(calls[0] == 0 && !readiness.closeAttempted(), "Clock expiry before the callback must prevent input");
        } else if (scenario.equals("close-throws")) {
            try {
                readiness.step(view(true, true, true, false), clock, 1000, () -> {
                    calls[0]++;
                    throw new IllegalStateException("native click rejected");
                });
                throw new AssertionError("Close failure was swallowed");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().equals("native click rejected"), "The original failure must propagate");
            }
            expect("WAIT", readiness.step(view(true, true, true, false), clock, 1000, close));
            require(calls[0] == 1 && readiness.closeAttempted(), "A failed Close must not silently emit again");
        } else if (scenario.equals("one-deadline")) {
            time[0] = 999;
            expect("CLOSE_ATTEMPTED", readiness.step(view(true, true, true, false), clock, 1000, close));
            time[0] = 1000;
            expect("EXPIRED", readiness.step(view(true, false, false, true), clock, 1000, close));
            require(calls[0] == 1, "Closing the modal must not renew the original deadline");
        } else throw new AssertionError("Unknown scenario " + scenario);
        System.out.println("PASS " + scenario);
    }
}
"""


class NativeFixtureReadinessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.classes = Path(cls.directory.name)
        probe = cls.classes / "FixtureReadinessProbe.java"
        probe.write_text(PROBE)
        subprocess.run(["javac", "--release", "8", "-d", str(cls.classes), str(SOURCE), str(probe)],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def prove(self, scenario):
        result = subprocess.run(["java", "-cp", str(self.classes), "FixtureReadinessProbe", scenario],
                                check=True, capture_output=True, text=True)
        self.assertEqual("PASS " + scenario, result.stdout.strip())

    def test_wait_close_once_wait_for_outgoing_modal_then_ready(self):
        self.prove("sequence")

    def test_modal_takes_precedence_over_a_label_in_the_same_snapshot(self):
        self.prove("modal-precedence")

    def test_incomplete_tree_neither_closes_nor_qualifies(self):
        self.prove("incomplete")

    def test_unresolved_close_does_not_claim_the_first_emission(self):
        self.prove("unresolved")

    def test_expiration_emits_no_input_and_cannot_qualify(self):
        self.prove("expired")

    def test_clock_is_revalidated_before_calling_close(self):
        self.prove("expires-before-close")

    def test_close_failure_propagates_without_a_second_emission(self):
        self.prove("close-throws")

    def test_close_does_not_renew_the_deadline(self):
        self.prove("one-deadline")


if __name__ == "__main__":
    unittest.main()
