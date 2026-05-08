package com.heikinashi.monitoring.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A Heikin-Ashi candle (CLAUDE.md §2 / §7). All arithmetic in
 * {@code BigDecimal} with {@link java.math.MathContext#DECIMAL64}.
 */
public record HABar(
        String instrumentId,
        Timeframe timeframe,
        Instant barTime,
        BigDecimal haOpen,
        BigDecimal haHigh,
        BigDecimal haLow,
        BigDecimal haClose,
        Instant computedAt) {

    public HABar {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(barTime, "barTime");
        Objects.requireNonNull(haOpen, "haOpen");
        Objects.requireNonNull(haHigh, "haHigh");
        Objects.requireNonNull(haLow, "haLow");
        Objects.requireNonNull(haClose, "haClose");
        Objects.requireNonNull(computedAt, "computedAt");
    }
}
