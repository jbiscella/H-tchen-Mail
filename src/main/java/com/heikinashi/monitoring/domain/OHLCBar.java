package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.error.OHLCInvariantViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A single OHLC bar at a point in time. Per CLAUDE.md §2: BigDecimal everywhere. */
public record OHLCBar(
        String instrumentId,
        Timeframe timeframe,
        Instant barTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Optional<BigDecimal> volume,
        String source,
        Instant ingestedAt) {

    public OHLCBar {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(barTime, "barTime");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
    }

    public OHLCBar withBarTime(Instant newBarTime) {
        return new OHLCBar(instrumentId, timeframe, newBarTime, open, high, low, close, volume, source, ingestedAt);
    }

    public OHLCBar withInstrumentId(String newInstrumentId) {
        return new OHLCBar(newInstrumentId, timeframe, barTime, open, high, low, close, volume, source, ingestedAt);
    }

    /** Throws {@link OHLCInvariantViolationException} if this bar is malformed. */
    public void validateInvariants() {
        if (open.signum() <= 0) throw violation("open");
        if (high.signum() <= 0) throw violation("high");
        if (low.signum() <= 0) throw violation("low");
        if (close.signum() <= 0) throw violation("close");
        if (high.compareTo(low) < 0) throw violation("high<low");
        if (high.compareTo(open) < 0) throw violation("high<open");
        if (high.compareTo(close) < 0) throw violation("high<close");
        if (low.compareTo(open) > 0) throw violation("low>open");
        if (low.compareTo(close) > 0) throw violation("low>close");
    }

    private OHLCInvariantViolationException violation(String field) {
        return new OHLCInvariantViolationException(barTime.toString(), field);
    }
}
