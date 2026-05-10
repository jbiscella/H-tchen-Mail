package com.heikinashi.monitoring.domain;

/** Result of a {@code monitoring-main} run (CLAUDE.md §10). */
public record MainSummary(
        int instrumentsProcessed,
        int instrumentsSucceeded,
        int instrumentsFailed,
        int instrumentsSkipped,
        int barsInserted,
        int eventsDetected,
        int alertsSent,
        int alertsQueued,
        int alertsSkipped,
        long durationMs,
        boolean softTimeoutHit) {

    public static MainSummary empty() {
        return new MainSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, false);
    }

    public MainSummary plusProcessed() {
        return new MainSummary(
                instrumentsProcessed + 1,
                instrumentsSucceeded,
                instrumentsFailed,
                instrumentsSkipped,
                barsInserted,
                eventsDetected,
                alertsSent,
                alertsQueued,
                alertsSkipped,
                durationMs,
                softTimeoutHit);
    }

    public MainSummary plusSucceeded() {
        return new MainSummary(
                instrumentsProcessed,
                instrumentsSucceeded + 1,
                instrumentsFailed,
                instrumentsSkipped,
                barsInserted,
                eventsDetected,
                alertsSent,
                alertsQueued,
                alertsSkipped,
                durationMs,
                softTimeoutHit);
    }

    public MainSummary plusFailed() {
        return new MainSummary(
                instrumentsProcessed,
                instrumentsSucceeded,
                instrumentsFailed + 1,
                instrumentsSkipped,
                barsInserted,
                eventsDetected,
                alertsSent,
                alertsQueued,
                alertsSkipped,
                durationMs,
                softTimeoutHit);
    }

    public MainSummary plusSkipped() {
        return new MainSummary(
                instrumentsProcessed,
                instrumentsSucceeded,
                instrumentsFailed,
                instrumentsSkipped + 1,
                barsInserted,
                eventsDetected,
                alertsSent,
                alertsQueued,
                alertsSkipped,
                durationMs,
                softTimeoutHit);
    }

    public MainSummary addBars(int n) {
        return new MainSummary(
                instrumentsProcessed,
                instrumentsSucceeded,
                instrumentsFailed,
                instrumentsSkipped,
                barsInserted + n,
                eventsDetected,
                alertsSent,
                alertsQueued,
                alertsSkipped,
                durationMs,
                softTimeoutHit);
    }

    public MainSummary addEvents(int n) {
        return new MainSummary(
                instrumentsProcessed,
                instrumentsSucceeded,
                instrumentsFailed,
                instrumentsSkipped,
                barsInserted,
                eventsDetected + n,
                alertsSent,
                alertsQueued,
                alertsSkipped,
                durationMs,
                softTimeoutHit);
    }

    public MainSummary withDispatch(DispatchSummary d) {
        return new MainSummary(
                instrumentsProcessed,
                instrumentsSucceeded,
                instrumentsFailed,
                instrumentsSkipped,
                barsInserted,
                eventsDetected,
                alertsSent + d.sent(),
                alertsQueued + d.queued(),
                alertsSkipped + d.skipped(),
                durationMs,
                softTimeoutHit);
    }

    public MainSummary withDuration(long ms) {
        return new MainSummary(
                instrumentsProcessed,
                instrumentsSucceeded,
                instrumentsFailed,
                instrumentsSkipped,
                barsInserted,
                eventsDetected,
                alertsSent,
                alertsQueued,
                alertsSkipped,
                ms,
                softTimeoutHit);
    }

    public MainSummary withSoftTimeoutHit() {
        return new MainSummary(
                instrumentsProcessed,
                instrumentsSucceeded,
                instrumentsFailed,
                instrumentsSkipped,
                barsInserted,
                eventsDetected,
                alertsSent,
                alertsQueued,
                alertsSkipped,
                durationMs,
                true);
    }
}
