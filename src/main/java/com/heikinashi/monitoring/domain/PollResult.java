package com.heikinashi.monitoring.domain;

/** Result of one {@code retry_poller} run (CLAUDE.md §9). */
public record PollResult(int processed, int sentFull, int sentDegraded, int requeued) {

    public PollResult plusProcessed() {
        return new PollResult(processed + 1, sentFull, sentDegraded, requeued);
    }

    public PollResult plusSentFull() {
        return new PollResult(processed, sentFull + 1, sentDegraded, requeued);
    }

    public PollResult plusSentDegraded() {
        return new PollResult(processed, sentFull, sentDegraded + 1, requeued);
    }

    public PollResult plusRequeued() {
        return new PollResult(processed, sentFull, sentDegraded, requeued + 1);
    }

    public static PollResult empty() {
        return new PollResult(0, 0, 0, 0);
    }
}
