package com.heikinashi.monitoring.domain.strategy;

import com.heikinashi.monitoring.domain.Timeframe;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The result of evaluating a strategy against the latest bar (Block 16): one
 * alert per (instrument, timeframe, bar) carrying one line per matched scenario.
 * When more than one scenario matches on the same bar, a single alert (hence a
 * single email) carries all matched lines.
 */
public record StrategyAlert(
        String instrumentId,
        String ticker,
        String exchange,
        Timeframe timeframe,
        Instant barTime,
        String strategyName,
        List<StrategyAlertLine> lines,
        Instant detectedAt) {

    public StrategyAlert {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(barTime, "barTime");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a StrategyAlert must carry at least one matched line");
        }
    }
}
