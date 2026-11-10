package dev.logisim.mobile.documents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class SnapshotCaptureRetryTest {
    @Test
    public void unavailableRootIsTypedWithoutAnotherHelperAttempt() {
        FakeClock clock = new FakeClock(2000);
        SnapshotCaptureRetry.RootUnavailableException unavailable =
                new SnapshotCaptureRetry.RootUnavailableException("example.app.e2e");
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
            clock.deadlines.add(deadline);
            clock.advance(1003);
            throw unavailable;
        });
        check(result.state == SnapshotCaptureRetry.State.ROOT_UNAVAILABLE && result.value == null,
                "An unavailable root must be typed without producing a value");
        check(result.error == unavailable && result.attempts == 1 && result.incompleteAttempts == 0,
                "Root unavailability must preserve its exact error and attempt counts");
        check(result.elapsedMillis == 1003 && result.overrunMillis == 3 && clock.sleeps.isEmpty(),
                "Root acquisition must retain the original helper budget and its overrun");
        check(clock.deadlines.equals(List.of(3000L)), "The root callback must receive the fixed 1000 ms budget");
        SnapshotCaptureRetry.Record record = result.records.get(0);
        check(record.state == SnapshotCaptureRetry.State.ROOT_UNAVAILABLE && record.error == unavailable
                        && record.elapsedMillis == 1003 && record.startedUptimeMillis == 2000,
                "Root failure must retain its exact history");
    }

    @Test
    public void unavailableRootAfterIncompleteRetainsMixedHistoryWithoutRetryingAgain() {
        FakeClock clock = new FakeClock(0);
        SnapshotCaptureRetry.RootUnavailableException unavailable =
                new SnapshotCaptureRetry.RootUnavailableException("example.app.e2e");
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
            clock.deadlines.add(deadline);
            if (clock.deadlines.size() == 1) {
                clock.advance(10);
                throw incomplete(2);
            }
            clock.advance(965);
            throw unavailable;
        });
        check(result.state == SnapshotCaptureRetry.State.ROOT_UNAVAILABLE && result.error == unavailable,
                "A later unavailable root must retain its typed terminal state");
        check(result.attempts == 2 && result.incompleteAttempts == 1 && result.elapsedMillis == 1000,
                "Mixed history must preserve both attempts within the same helper deadline");
        check(result.records.get(0).state == SnapshotCaptureRetry.State.INCOMPLETE_TREE
                        && result.records.get(1).state == SnapshotCaptureRetry.State.ROOT_UNAVAILABLE,
                "Mixed history must not be rewritten as a single root failure");
        check(clock.sleeps.equals(List.of(25L)) && clock.deadlines.equals(List.of(1000L, 1000L)),
                "Only the existing incomplete-tree retry may sleep or start another helper attempt");
    }

    @Test
    public void genericExceptionWithRootTextRemainsAnError() {
        FakeClock clock = new FakeClock(0);
        IllegalStateException failure = new IllegalStateException(
                new SnapshotCaptureRetry.RootUnavailableException("example.app.e2e").getMessage());
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> { throw failure; });
        check(result.state == SnapshotCaptureRetry.State.ERROR && result.error == failure && result.attempts == 1,
                "Root typing must depend on the exact exception type, never its text");
        check(result.value == null && clock.sleeps.isEmpty(), "Generic failures must not retry or return a partial value");
    }

    @Test
    public void completeFirstAttempt() {
        FakeClock clock = new FakeClock(4000);
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
            clock.deadlines.add(deadline);
            clock.advance(7);
            return "snapshot";
        });
        check(result.state == SnapshotCaptureRetry.State.COMPLETE, "First attempt must complete");
        check("snapshot".equals(result.value), "Completed value must be returned");
        check(result.error == null, "Completed result must have no error");
        check(result.attempts == 1 && result.incompleteAttempts == 0, "First completion must not retry");
        check(result.elapsedMillis == 7 && result.overrunMillis == 0, "First completion timing must be recorded");
        check(clock.sleeps.isEmpty(), "First completion must not sleep");
        check(clock.deadlines.equals(List.of(5000L)), "Deadline must start at the initial uptime");
        check(result.records.size() == 1 && result.droppedRecords == 0, "First completion must have one record");
        SnapshotCaptureRetry.Record record = result.records.get(0);
        check(record.attempt == 1 && record.startedUptimeMillis == 4000 && record.elapsedMillis == 7,
                "First record must preserve its start and duration");
        check(record.state == SnapshotCaptureRetry.State.COMPLETE && record.error == null,
                "First record must report completion");
    }

    @Test
    public void incompleteThenComplete() {
        FakeClock clock = new FakeClock(0);
        SnapshotCaptureRetry.IncompleteTreeException incomplete = incomplete(2);
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
            clock.deadlines.add(deadline);
            if (clock.deadlines.size() == 1) {
                clock.advance(10);
                throw incomplete;
            }
            clock.advance(5);
            return "recovered";
        });
        check(result.state == SnapshotCaptureRetry.State.COMPLETE && "recovered".equals(result.value),
                "An incomplete tree must be retried to completion");
        check(result.error == null, "The recovered result must not retain the incomplete error");
        check(result.attempts == 2 && result.incompleteAttempts == 1, "Recovery counts must include the incomplete attempt");
        check(clock.sleeps.equals(List.of(25L)), "Recovery must pause for 25 ms");
        check(result.elapsedMillis == 40 && result.overrunMillis == 0, "Recovery timing must include the pause");
        check(clock.deadlines.equals(List.of(1000L, 1000L)), "Recovery must reuse the original deadline");
        check(result.records.get(0).state == SnapshotCaptureRetry.State.INCOMPLETE_TREE
                        && result.records.get(0).error == incomplete,
                "The incomplete record must retain its error");
        check(result.records.get(1).startedUptimeMillis == 35 && result.records.get(1).elapsedMillis == 5,
                "The recovered record must start after the pause");
        check("/0/2".equals(incomplete.parentPath) && incomplete.childIndex == 2
                        && incomplete.parentChildCount == 4 && incomplete.windowId == 123 && incomplete.nodeCount == 7,
                "Incomplete errors must preserve tree diagnostics");
        check(incomplete.getMessage().contains("child is unavailable at index 2"),
                "Incomplete error message must identify the missing child");
    }

    @Test
    public void incompleteAt990StopsAtDeadline() {
        FakeClock clock = new FakeClock(0);
        SnapshotCaptureRetry.IncompleteTreeException incomplete = incomplete(0);
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
            clock.deadlines.add(deadline);
            clock.advance(990);
            throw incomplete;
        });
        check(result.state == SnapshotCaptureRetry.State.INCOMPLETE_TREE && result.value == null,
                "An expired incomplete tree must return no partial value");
        check(result.error == incomplete, "Exhaustion must return the last incomplete error");
        check(result.attempts == 1 && result.incompleteAttempts == 1, "No attempt may begin at the deadline");
        check(clock.sleeps.equals(List.of(10L)), "The last pause must use only the remaining 10 ms");
        check(result.elapsedMillis == 1000 && result.overrunMillis == 0, "Exhaustion must include the final pause");
        check(clock.deadlines.equals(List.of(1000L)), "The callback must receive the original deadline");
    }

    @Test
    public void completeAfterDeadlineIsAccepted() {
        FakeClock clock = new FakeClock(0);
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
            clock.deadlines.add(deadline);
            clock.advance(1100);
            return "late snapshot";
        });
        check(result.state == SnapshotCaptureRetry.State.COMPLETE && "late snapshot".equals(result.value),
                "A complete in-flight attempt must remain successful after the deadline");
        check(result.elapsedMillis == 1100 && result.overrunMillis == 100, "A late completion must record its overrun");
        check(result.attempts == 1 && clock.sleeps.isEmpty(), "A late completion must not retry");
        check(result.records.get(0).elapsedMillis == 1100, "A late attempt record must preserve its full duration");
        check(clock.deadlines.equals(List.of(1000L)), "A late callback must still receive the original deadline");
    }

    @Test
    public void ordinaryExceptionsDoNotRetry() {
        Exception[] failures = {new IllegalStateException("bad state"), new IOException("I/O failed"),
                new SecurityException("wrong target")};
        for (Exception failure : failures) {
            FakeClock clock = new FakeClock(100);
            SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
                clock.deadlines.add(deadline);
                clock.advance(3);
                throw failure;
            });
            check(result.state == SnapshotCaptureRetry.State.ERROR && result.error == failure && result.value == null,
                    "An ordinary exception must terminate with its original error");
            check(result.attempts == 1 && result.incompleteAttempts == 0 && clock.sleeps.isEmpty(),
                    "An ordinary exception must not retry");
            check(result.elapsedMillis == 3 && result.records.get(0).state == SnapshotCaptureRetry.State.ERROR,
                    "An ordinary exception must record its duration and state");
            check(clock.deadlines.equals(List.of(1100L)), "An error callback must receive the fixed deadline");
        }
    }

    @Test
    public void incompleteAfterDeadlineIsRejected() {
        FakeClock clock = new FakeClock(0);
        SnapshotCaptureRetry.IncompleteTreeException incomplete = incomplete(7);
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
            clock.deadlines.add(deadline);
            clock.advance(1100);
            throw incomplete;
        });
        check(result.state == SnapshotCaptureRetry.State.INCOMPLETE_TREE && result.value == null,
                "An incomplete in-flight attempt must never become a completed snapshot");
        check(result.error == incomplete && result.attempts == 1 && result.incompleteAttempts == 1,
                "A late incomplete attempt must retain its error without retrying");
        check(result.elapsedMillis == 1100 && result.overrunMillis == 100 && clock.sleeps.isEmpty(),
                "A late incomplete attempt must record overrun without another pause");
        check(clock.deadlines.equals(List.of(1000L)), "A late incomplete callback must keep the original deadline");
    }

    @Test
    public void nullValueIsNotComplete() {
        FakeClock clock = new FakeClock(0);
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> null);
        check(result.state == SnapshotCaptureRetry.State.ERROR && result.value == null && result.error != null,
                "A missing completed value must be a factual error");
        check(result.attempts == 1 && result.incompleteAttempts == 0 && clock.sleeps.isEmpty(),
                "A missing completed value must not retry");
    }

    @Test
    public void fortyIncompleteAttemptsKeepLastSixteen() {
        FakeClock clock = new FakeClock(0);
        List<SnapshotCaptureRetry.IncompleteTreeException> failures = new ArrayList<>();
        SnapshotCaptureRetry.Result<String> result = SnapshotCaptureRetry.run(clock, deadline -> {
            clock.deadlines.add(deadline);
            SnapshotCaptureRetry.IncompleteTreeException failure = incomplete(failures.size());
            failures.add(failure);
            throw failure;
        });
        check(result.state == SnapshotCaptureRetry.State.INCOMPLETE_TREE && result.value == null,
                "Repeated incomplete trees must stop without a partial value");
        check(result.attempts == 40 && result.incompleteAttempts == 40, "The fixed budget must permit exactly 40 instant attempts");
        check(result.error == failures.get(39), "Exhaustion must retain the final incomplete error");
        check(result.elapsedMillis == 1000 && result.overrunMillis == 0, "Repeated attempts must share one budget");
        check(result.records.size() == 16 && result.droppedRecords == 24, "History must retain the last 16 records and count drops");
        check(result.records.get(0).attempt == 25 && result.records.get(15).attempt == 40,
                "History must preserve the final attempt numbers");
        for (int index = 0; index < result.records.size(); index++) {
            SnapshotCaptureRetry.Record record = result.records.get(index);
            check(record.startedUptimeMillis == (record.attempt - 1) * 25L && record.startedUptimeMillis < 1000,
                    "No retained attempt may begin at or after the deadline");
            check(record.state == SnapshotCaptureRetry.State.INCOMPLETE_TREE
                            && record.error == failures.get(record.attempt - 1),
                    "Each retained attempt must preserve its incomplete error");
        }
        check(clock.sleeps.size() == 40 && clock.deadlines.size() == 40, "Every incomplete attempt must consume its remaining pause");
        for (long pause : clock.sleeps) {
            check(pause == 25, "Repeated attempts must pause for 25 ms");
        }
        for (long deadline : clock.deadlines) {
            check(deadline == 1000, "The callback deadline must never reset");
        }
    }

    private static SnapshotCaptureRetry.IncompleteTreeException incomplete(int childIndex) {
        return new SnapshotCaptureRetry.IncompleteTreeException("/0/2", childIndex, 4, 123, 7);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeClock implements SnapshotCaptureRetry.Clock {
        long uptime;
        final List<Long> sleeps = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();

        FakeClock(long uptime) {
            this.uptime = uptime;
        }

        @Override
        public long uptimeMillis() {
            return uptime;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            advance(millis);
        }

        void advance(long millis) {
            uptime += millis;
        }
    }
}
