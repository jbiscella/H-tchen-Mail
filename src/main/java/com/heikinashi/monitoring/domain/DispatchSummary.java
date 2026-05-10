package com.heikinashi.monitoring.domain;

/** Result of {@code dispatch_alerts} (CLAUDE.md §9). */
public record DispatchSummary(int sent, int failed, int queued, int skipped) {

    public DispatchSummary plusSent() {
        return new DispatchSummary(sent + 1, failed, queued, skipped);
    }

    public DispatchSummary plusFailed() {
        return new DispatchSummary(sent, failed + 1, queued, skipped);
    }

    public DispatchSummary plusQueued() {
        return new DispatchSummary(sent, failed, queued + 1, skipped);
    }

    public DispatchSummary plusSkipped() {
        return new DispatchSummary(sent, failed, queued, skipped + 1);
    }

    public static DispatchSummary empty() {
        return new DispatchSummary(0, 0, 0, 0);
    }
}
