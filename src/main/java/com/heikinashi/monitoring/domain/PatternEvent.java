package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A detected Heikin-Ashi pattern event (CLAUDE.md §8). Pure data — Block 5
 * produces these and Block 6 consumes them to dispatch alerts.
 */
public record PatternEvent(
        String instrumentId,
        String ticker,
        String exchange,
        Timeframe timeframe,
        Instant barTime,
        PatternKind pattern,
        PatternSubtype subtype,
        Map<String, Object> paramsUsed,
        BarSnapshot barSnapshot,
        Instant detectedAt) {

    public PatternEvent {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(barTime, "barTime");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(subtype, "subtype");
        Objects.requireNonNull(paramsUsed, "paramsUsed");
        Objects.requireNonNull(barSnapshot, "barSnapshot");
        Objects.requireNonNull(detectedAt, "detectedAt");
        paramsUsed = Map.copyOf(paramsUsed);
    }
}
