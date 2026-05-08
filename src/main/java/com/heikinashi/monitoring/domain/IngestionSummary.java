package com.heikinashi.monitoring.domain;

/** Result of an {@code ingest_all_active()} run (CLAUDE.md §6). */
public record IngestionSummary(int processed, int succeeded, int failed, int barsInserted, long durationMs) {

    public IngestionSummary plusInserted(int delta) {
        return new IngestionSummary(processed, succeeded, failed, barsInserted + delta, durationMs);
    }

    public IngestionSummary plusProcessed() {
        return new IngestionSummary(processed + 1, succeeded, failed, barsInserted, durationMs);
    }

    public IngestionSummary plusSucceeded() {
        return new IngestionSummary(processed, succeeded + 1, failed, barsInserted, durationMs);
    }

    public IngestionSummary plusFailed() {
        return new IngestionSummary(processed, succeeded, failed + 1, barsInserted, durationMs);
    }

    public IngestionSummary withDuration(long ms) {
        return new IngestionSummary(processed, succeeded, failed, barsInserted, ms);
    }

    public static IngestionSummary empty() {
        return new IngestionSummary(0, 0, 0, 0, 0);
    }

    public double failureRate() {
        return processed == 0 ? 0.0 : (double) failed / processed;
    }
}
