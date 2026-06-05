package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A strategy alert whose dispatch failed, queued for retry (CLAUDE.md §2
 * STRATEGY_PENDING_ALERT, §9 Component 1c SI-3c.3).
 *
 * <p>Unlike a {@link PendingAlert} (which wraps a self-contained
 * {@link PatternEvent}), a strategy alert's chart cannot be re-derived from the
 * alert alone — it needs the {@code Strategy} + the HA bar window. So this item
 * carries the <b>already-rendered chart</b> ({@link #chart()}, absent only when
 * the chart stage itself failed); the poller reuses it rather than reconstructing
 * anything, re-running only the AI analyst from {@link #alert()}.
 *
 * <p>Identified by a deterministic {@code eventUid =
 * <instrument_id>_<tf>_<bar_time>_strategy} so concurrent pollers see one item.
 */
public record PendingStrategyAlert(
        String eventUid,
        StrategyAlert alert,
        Optional<ChartImage> chart,
        int retryCount,
        Instant retryAt,
        PendingAlert.LastError lastError,
        Instant createdAt) {

    public PendingStrategyAlert {
        Objects.requireNonNull(eventUid, "eventUid");
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(chart, "chart");
        Objects.requireNonNull(retryAt, "retryAt");
        Objects.requireNonNull(lastError, "lastError");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public PendingStrategyAlert bumped(Instant nextRetryAt, PendingAlert.LastError newError) {
        return new PendingStrategyAlert(eventUid, alert, chart, retryCount + 1, nextRetryAt, newError, createdAt);
    }

    public static String uidOf(StrategyAlert alert) {
        return alert.instrumentId() + "_" + alert.timeframe().wire() + "_" + alert.barTime() + "_strategy";
    }
}
