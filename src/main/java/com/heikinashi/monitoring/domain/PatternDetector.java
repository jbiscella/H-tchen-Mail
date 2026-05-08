package com.heikinashi.monitoring.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

/**
 * Pure HA pattern detection (CLAUDE.md §8). No I/O, deterministic, BigDecimal
 * arithmetic with {@link MathContext#DECIMAL64} for ratio calculations.
 *
 * <p>The detector functions take a single {@code current} HA bar and any
 * required prior context; the orchestrator (PatternDetectionService) is in
 * charge of feeding the right history slice.
 */
public final class PatternDetector {

    private static final MathContext MC = MathContext.DECIMAL64;

    private PatternDetector() {}

    /**
     * Color-change reversal detection. {@code prevContiguous} are the bars
     * immediately preceding {@code current} in the chain, oldest-first; only
     * the most recent {@code minStreakLength} are inspected.
     */
    public static Optional<PatternSubtype> detectColorChange(
            HABar current, List<HABar> prevContiguous, int minStreakLength) {
        if (minStreakLength < 1) {
            return Optional.empty();
        }
        BarColor curColor = BarColor.of(current);
        if (curColor == BarColor.NEUTRAL) {
            return Optional.empty();
        }
        if (prevContiguous.size() < minStreakLength) {
            return Optional.empty();
        }
        BarColor required = curColor == BarColor.GREEN ? BarColor.RED : BarColor.GREEN;
        // Inspect the LAST minStreakLength entries (the streak ending just before current).
        for (int i = prevContiguous.size() - minStreakLength; i < prevContiguous.size(); i++) {
            if (BarColor.of(prevContiguous.get(i)) != required) {
                return Optional.empty();
            }
        }
        return Optional.of(
                curColor == BarColor.GREEN ? PatternSubtype.BULLISH_REVERSAL : PatternSubtype.BEARISH_REVERSAL);
    }

    /**
     * Strong-candle detection. Returns empty when the bar's range is zero.
     *
     * @param wickTolerance maximum {@code (lower|upper)_wick / ha_close} below which the wick is
     *                      considered absent
     * @param minBodyRatio  minimum {@code body / range}
     */
    public static Optional<PatternSubtype> detectStrongCandle(
            HABar current, BigDecimal wickTolerance, BigDecimal minBodyRatio) {
        BarColor color = BarColor.of(current);
        if (color == BarColor.NEUTRAL) {
            return Optional.empty();
        }
        BigDecimal range = current.haHigh().subtract(current.haLow());
        if (range.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal body = current.haClose().subtract(current.haOpen()).abs();
        BigDecimal bodyRatio = body.divide(range, MC);
        if (bodyRatio.compareTo(minBodyRatio) < 0) {
            return Optional.empty();
        }
        BigDecimal upperWick = current.haHigh().subtract(current.haOpen().max(current.haClose()));
        BigDecimal lowerWick = current.haOpen().min(current.haClose()).subtract(current.haLow());
        if (color == BarColor.GREEN) {
            BigDecimal lowerWickRatio = lowerWick.divide(current.haClose(), MC);
            return lowerWickRatio.compareTo(wickTolerance) < 0
                    ? Optional.of(PatternSubtype.BULLISH_STRONG)
                    : Optional.empty();
        }
        BigDecimal upperWickRatio = upperWick.divide(current.haClose(), MC);
        return upperWickRatio.compareTo(wickTolerance) < 0
                ? Optional.of(PatternSubtype.BEARISH_STRONG)
                : Optional.empty();
    }

    /** Doji detection. Returns empty when the bar's range is zero. */
    public static Optional<PatternSubtype> detectDoji(HABar current, BigDecimal maxBodyRatio) {
        BigDecimal range = current.haHigh().subtract(current.haLow());
        if (range.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal body = current.haClose().subtract(current.haOpen()).abs();
        BigDecimal bodyRatio = body.divide(range, MC);
        return bodyRatio.compareTo(maxBodyRatio) <= 0 ? Optional.of(PatternSubtype.DOJI) : Optional.empty();
    }
}
