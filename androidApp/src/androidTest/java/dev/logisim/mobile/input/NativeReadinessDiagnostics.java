package dev.logisim.mobile.input;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded value records and wait outcomes. No Android objects are retained here. */
public final class NativeReadinessDiagnostics {
    private NativeReadinessDiagnostics() {}

    public static final class Entry<T> {
        public final long sequence;
        public final T value;

        private Entry(long sequence, T value) {
            this.sequence = sequence;
            this.value = value;
        }
    }

    public static final class Snapshot<T> {
        public final int capacity;
        public final long totalRecords;
        public final long droppedRecords;
        public final List<Entry<T>> records;

        private Snapshot(int capacity, long totalRecords, List<Entry<T>> records) {
            this.capacity = capacity;
            this.totalRecords = totalRecords;
            this.droppedRecords = totalRecords - records.size();
            this.records = Collections.unmodifiableList(records);
        }
    }

    public static final class Trace<T> {
        private final int capacity;
        private final ArrayDeque<Entry<T>> records = new ArrayDeque<>();
        private long totalRecords;

        public Trace(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("Trace capacity must be positive");
            this.capacity = capacity;
        }

        public void record(T value) {
            if (records.size() == capacity) records.removeFirst();
            records.addLast(new Entry<>(++totalRecords, value));
        }

        public Snapshot<T> snapshot() {
            return new Snapshot<>(capacity, totalRecords, new ArrayList<>(records));
        }
    }

    public enum WaitOutcome { NOT_EVALUATED, RETURNED_FALSE, THREW, READY }

    public static final class WaitState {
        private int attempts;
        private WaitOutcome outcome = WaitOutcome.NOT_EVALUATED;
        private long lastOutcomeAtMillis;
        private String historicalException;
        private int historicalExceptionAttempt;
        private long historicalExceptionAtMillis;

        public void returned(boolean ready, long atMillis) {
            attempts++;
            outcome = ready ? WaitOutcome.READY : WaitOutcome.RETURNED_FALSE;
            lastOutcomeAtMillis = atMillis;
        }

        public void threw(String exception, long atMillis) {
            attempts++;
            outcome = WaitOutcome.THREW;
            lastOutcomeAtMillis = atMillis;
            historicalException = exception;
            historicalExceptionAttempt = attempts;
            historicalExceptionAtMillis = atMillis;
        }

        public int attempts() { return attempts; }
        public WaitOutcome outcome() { return outcome; }
        public long lastOutcomeAtMillis() { return lastOutcomeAtMillis; }
        public String historicalException() { return historicalException; }
        public int historicalExceptionAttempt() { return historicalExceptionAttempt; }
        public long historicalExceptionAtMillis() { return historicalExceptionAtMillis; }
    }
}
