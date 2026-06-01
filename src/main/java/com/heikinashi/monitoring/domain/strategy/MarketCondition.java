package com.heikinashi.monitoring.domain.strategy;

import java.math.BigDecimal;

/**
 * A single market condition in an H-tchen strategy scenario (Blocks 15-16).
 *
 * <p>This is H-tchen's own vocabulary — pure domain, free of any nachtkrapp
 * type. The translation from a {@code MarketCondition} to a nachtkrapp
 * {@code DetectionRule} (and the correlation back to a {@code PatternMatch})
 * lives in one place in the infrastructure layer; that translation is also the
 * single point that rejects an unsupported condition (fail loud, never partial).
 *
 * <p>Every variant maps to a nachtkrapp <em>event</em>-flavoured rule, so a
 * condition is true exactly on the bar where the transition occurs — which makes
 * per-scenario transition gating (Block 16) intrinsic.
 */
public sealed interface MarketCondition {

    /** Heikin Ashi colour flip after a same-colour streak (legacy {@code color_change}). */
    record ColorChange(int minStreakLength, Side side) implements MarketCondition {}

    /** Heikin Ashi strong candle: large body, negligible wick on the trend side (legacy {@code strong_candle}). */
    record StrongCandle(BigDecimal wickTolerance, BigDecimal minBodyRatio, Side side) implements MarketCondition {}

    /** Heikin Ashi doji: body small relative to range (legacy {@code doji}). */
    record Doji(BigDecimal maxBodyRatio) implements MarketCondition {}

    /** One moving average crossing another (e.g. EMA(50) crossing SMA(200)). */
    record MovingAverageCross(
            MaKind fastType, int fastPeriod, MaKind slowType, int slowPeriod, Side side, PriceField source)
            implements MarketCondition {}

    /** RSI crossing its 50 midline. */
    record RsiMidlineCross(int period, Side side, PriceField source) implements MarketCondition {}
}
