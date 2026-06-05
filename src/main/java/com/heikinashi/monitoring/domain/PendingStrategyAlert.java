package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import java.time.Instant;
import java.util.Objects;

/**
 * A strategy alert whose dispatch failed, queued for retry (CLAUDE.md §2
 * STRATEGY_PENDING_ALERT, §9 Component 1c SI-3c.3).
 *
 * <p>Stores only the alert + retry bookkeeping — <b>never the chart</b>. The
 * strategy is persisted (the {@code STRATEGY} item) and bars are readable by
 * count, so the retry poller re-renders the chart from the persisted
 * {@code Strategy} + bars rather than carrying a (potentially &gt;400 KB) PNG blob
 * in the row.
 *
 * <p>Identified by a deterministic {@code eventUid =
 * <instrument_id>_<tf>_<bar_time>_strategy} so concurrent pollers see one item.
 */
public record PendingStrategyAlert(
        String eventUid,
        StrategyAlert alert,
        int retryCount,
        Instant retryAt,
        PendingAlert.LastError lastError,
        Instant createdAt) {

    public PendingStrategyAlert {
        Objects.requireNonNull(eventUid, "eventUid");
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(retryAt, "retryAt");
        Objects.requireNonNull(lastError, "lastError");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public PendingStrategyAlert bumped(Instant nextRetryAt, PendingAlert.LastError newError) {
        return new PendingStrategyAlert(eventUid, alert, retryCount + 1, nextRetryAt, newError, createdAt);
    }

    public static String uidOf(StrategyAlert alert) {
        return alert.instrumentId() + "_" + alert.timeframe().wire() + "_" + alert.barTime() + "_strategy";
    }
}
