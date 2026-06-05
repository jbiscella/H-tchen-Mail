package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the strategy-alert retry queue (CLAUDE.md §9 Component 1c
 * SI-3c.3). Same contract as {@link PendingAlertRepository}, over the
 * {@code STRATEGY_PENDING_ALERT} partition:
 *
 * <ul>
 *   <li>{@link #enqueue(PendingStrategyAlert)} writes idempotently — a second
 *       enqueue for the same {@code event_uid} is a no-op.</li>
 *   <li>{@link #bumpRetry(PendingStrategyAlert, int)} uses a conditional update
 *       on {@code retry_count} to be safe under concurrent pollers.</li>
 *   <li>{@link #queryDue(Instant, int)} returns items with {@code retry_at <= now},
 *       ascending by {@code retry_at}.</li>
 * </ul>
 */
public interface PendingStrategyAlertRepository {

    void enqueue(PendingStrategyAlert pending);

    Optional<PendingStrategyAlert> findByUid(String eventUid);

    List<PendingStrategyAlert> queryDue(Instant now, int limit);

    boolean bumpRetry(PendingStrategyAlert updated, int expectedRetryCount);

    void delete(String eventUid);
}
