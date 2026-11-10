package dev.logisim.mobile.documents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class SnapshotCaptureRetry {
    private static final long BUDGET_MILLIS = 1000;
    private static final long RETRY_INTERVAL_MILLIS = 25;
    private static final int HISTORY_LIMIT = 16;

    enum State {
        COMPLETE,
        INCOMPLETE_TREE,
        ROOT_UNAVAILABLE,
        ERROR
    }

    interface Clock {
        long uptimeMillis();

        void sleep(long millis);
    }

    interface Attempt<T> {
        T capture(long absoluteDeadline) throws Exception;
    }

    static final class RootUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;

        RootUnavailableException(String expectedPackage) {
            super("UiAutomation returned no active root or active/focused application window root for "
                    + expectedPackage + " before the capture retry deadline");
        }
    }

    static final class IncompleteTreeException extends IOException {
        private static final long serialVersionUID = 1L;

        final String parentPath;
        final int childIndex;
        final int parentChildCount;
        final int windowId;
        final int nodeCount;

        IncompleteTreeException(String parentPath, int childIndex, int parentChildCount,
                int windowId, int nodeCount) {
            super("Accessibility child is unavailable at index " + childIndex + " of " + parentChildCount
                    + " under " + parentPath + ", windowId=" + windowId + ", nodeCount=" + nodeCount);
            this.parentPath = parentPath;
            this.childIndex = childIndex;
            this.parentChildCount = parentChildCount;
            this.windowId = windowId;
            this.nodeCount = nodeCount;
        }
    }

    static final class Record {
        final int attempt;
        final long startedUptimeMillis;
        final long elapsedMillis;
        final State state;
        final Exception error;

        Record(int attempt, long startedUptimeMillis, long elapsedMillis, State state, Exception error) {
            this.attempt = attempt;
            this.startedUptimeMillis = startedUptimeMillis;
            this.elapsedMillis = elapsedMillis;
            this.state = state;
            this.error = error;
        }
    }

    static final class Result<T> {
        final T value;
        final Exception error;
        final State state;
        final int attempts;
        final int incompleteAttempts;
        final long elapsedMillis;
        final long overrunMillis;
        final List<Record> records;
        final int droppedRecords;

        Result(T value, Exception error, State state, int attempts, int incompleteAttempts,
                long elapsedMillis, List<Record> records, int droppedRecords) {
            this.value = value;
            this.error = error;
            this.state = state;
            this.attempts = attempts;
            this.incompleteAttempts = incompleteAttempts;
            this.elapsedMillis = elapsedMillis;
            this.overrunMillis = Math.max(0, elapsedMillis - BUDGET_MILLIS);
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            this.droppedRecords = droppedRecords;
        }
    }

    static <T> Result<T> run(Clock clock, Attempt<T> attempt) {
        long started = clock.uptimeMillis();
        long deadline = started + BUDGET_MILLIS;
        long attemptStarted = started;
        int attempts = 0;
        int incompleteAttempts = 0;
        int droppedRecords = 0;
        Exception lastIncomplete = null;
        List<Record> records = new ArrayList<>(HISTORY_LIMIT);

        while (attemptStarted < deadline) {
            attempts++;
            T value = null;
            Exception error = null;
            State state;
            try {
                value = Objects.requireNonNull(attempt.capture(deadline), "Capture returned no complete value");
                state = State.COMPLETE;
            } catch (IncompleteTreeException incomplete) {
                error = incomplete;
                lastIncomplete = incomplete;
                incompleteAttempts++;
                state = State.INCOMPLETE_TREE;
            } catch (RootUnavailableException unavailable) {
                error = unavailable;
                state = State.ROOT_UNAVAILABLE;
            } catch (Exception failure) {
                error = failure;
                state = State.ERROR;
            }
            long finished = clock.uptimeMillis();
            if (records.size() == HISTORY_LIMIT) {
                records.remove(0);
                droppedRecords++;
            }
            records.add(new Record(attempts, attemptStarted, finished - attemptStarted, state, error));
            if (state != State.INCOMPLETE_TREE) {
                return new Result<>(value, error, state, attempts, incompleteAttempts,
                        finished - started, records, droppedRecords);
            }
            long remaining = deadline - finished;
            if (remaining <= 0) {
                return new Result<>(null, lastIncomplete, State.INCOMPLETE_TREE, attempts, incompleteAttempts,
                        finished - started, records, droppedRecords);
            }
            clock.sleep(Math.min(RETRY_INTERVAL_MILLIS, remaining));
            attemptStarted = clock.uptimeMillis();
        }
        return new Result<>(null, lastIncomplete, State.INCOMPLETE_TREE, attempts, incompleteAttempts,
                attemptStarted - started, records, droppedRecords);
    }

    private SnapshotCaptureRetry() {}
}
