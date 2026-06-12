package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

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
 * <p>{@code triggerBar} is the raw OHLC bar that fired the alert (the strategy
 * chart is built from the raw series, with Heikin-Ashi candles drawn by
 * {@code CandleStyle.HEIKIN_ASHI} — §9 Component 1b). Under {@code SNAPSHOT_ONLY}
 * retention a later ingest can evict it before the retry runs, so the poller
 * synthesizes it back into the series (heerwisch requires the entry/exit marker
 * to sit on a bar that is present — V7); empty only for pending items written
 * before this snapshot was added.
 *
 * <p>Identified by a deterministic {@code eventUid =
 * <instrument_id>_<tf>_<bar_time>_strategy} so concurrent pollers see one item.
 */
public record PendingStrategyAlert(
        String eventUid,
        StrategyAlert alert,
        Optional<OHLCBar> triggerBar,
        int retryCount,
        Instant retryAt,
        PendingAlert.LastError lastError,
        Instant createdAt) {

    public PendingStrategyAlert {
        Objects.requireNonNull(eventUid, "eventUid");
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(triggerBar, "triggerBar");
        Objects.requireNonNull(retryAt, "retryAt");
        Objects.requireNonNull(lastError, "lastError");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public PendingStrategyAlert bumped(Instant nextRetryAt, PendingAlert.LastError newError) {
        return new PendingStrategyAlert(eventUid, alert, triggerBar, retryCount + 1, nextRetryAt, newError, createdAt);
    }

    public static String uidOf(StrategyAlert alert) {
        return alert.instrumentId() + "_" + alert.timeframe().wire() + "_" + alert.barTime() + "_strategy";
    }
}
