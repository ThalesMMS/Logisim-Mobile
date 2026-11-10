package dev.logisim.mobile.input;

import java.util.function.LongSupplier;

/** One import's observed preparation sequence, without Android objects or another clock budget. */
public final class NativeFixtureReadiness {
    public enum Outcome { WAIT, CLOSE_ATTEMPTED, READY, EXPIRED }

    public static final class Observation {
        public final boolean complete;
        public final boolean importReportPresent;
        public final boolean closeAvailable;
        public final boolean primaryLabelFound;

        public Observation(boolean complete, boolean importReportPresent,
                           boolean closeAvailable, boolean primaryLabelFound) {
            this.complete = complete;
            this.importReportPresent = importReportPresent;
            this.closeAvailable = closeAvailable;
            this.primaryLabelFound = primaryLabelFound;
        }
    }

    private boolean closeAttempted;

    public boolean closeAttempted() { return closeAttempted; }

    public Outcome step(Observation observed, LongSupplier clock, long deadlineMillis, Runnable close) {
        if (clock.getAsLong() >= deadlineMillis) return Outcome.EXPIRED;
        if (!observed.complete) return Outcome.WAIT;
        if (observed.importReportPresent) {
            if (closeAttempted || !observed.closeAvailable) return Outcome.WAIT;
            if (clock.getAsLong() >= deadlineMillis) return Outcome.EXPIRED;
            closeAttempted = true;
            close.run();
            return Outcome.CLOSE_ATTEMPTED;
        }
        return observed.primaryLabelFound ? Outcome.READY : Outcome.WAIT;
    }
}
