package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the retry queue (CLAUDE.md §9).
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>{@link #enqueue(PendingAlert)} writes idempotently — a second enqueue
 *       for the same {@code event_uid} is a no-op (the original retry_count
 *       and retry_at win).</li>
 *   <li>{@link #bumpRetry(PendingAlert, int)} uses a conditional update on
 *       {@code retry_count} to be safe under concurrent pollers.</li>
 *   <li>{@link #queryDue(Instant, int)} returns items with
 *       {@code retry_at <= now}, ascending by {@code retry_at}.</li>
 * </ul>
 */
public interface PendingAlertRepository {

    void enqueue(PendingAlert pending);

    Optional<PendingAlert> findByUid(String eventUid);

    List<PendingAlert> queryDue(Instant now, int limit);

    /**
     * Conditionally bump retry state. Returns true if the underlying store
     * accepted the update (i.e. {@code retry_count} was {@code expectedRetryCount}
     * at write time), false if the guard failed (concurrent poller won).
     */
    boolean bumpRetry(PendingAlert updated, int expectedRetryCount);

    void delete(String eventUid);
}
