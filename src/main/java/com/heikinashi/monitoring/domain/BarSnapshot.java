package com.heikinashi.monitoring.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Snapshot of OHLC + HA values attached to each {@link PatternEvent} (CLAUDE.md §8). */
public record BarSnapshot(
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Optional<BigDecimal> volume,
        BigDecimal haOpen,
        BigDecimal haHigh,
        BigDecimal haLow,
        BigDecimal haClose) {

    public BarSnapshot {
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(haOpen, "haOpen");
        Objects.requireNonNull(haHigh, "haHigh");
        Objects.requireNonNull(haLow, "haLow");
        Objects.requireNonNull(haClose, "haClose");
    }

    public static BarSnapshot from(OHLCBar ohlc, HABar ha) {
        return new BarSnapshot(
                ohlc.open(),
                ohlc.high(),
                ohlc.low(),
                ohlc.close(),
                ohlc.volume(),
                ha.haOpen(),
                ha.haHigh(),
                ha.haLow(),
                ha.haClose());
    }
}
