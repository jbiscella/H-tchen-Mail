package com.heikinashi.monitoring.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure HA calculation (CLAUDE.md §7). No I/O, no clocks, no Decimal drift —
 * arithmetic uses {@link MathContext#DECIMAL64}.
 *
 * <pre>
 *   ha_close[t] = (open + high + low + close) / 4
 *   ha_open[t]  = (ha_open[t-1] + ha_close[t-1]) / 2          (t ≥ 1)
 *   ha_open[0]  = (open + close) / 2                          (seed)
 *   ha_high[t]  = max(high, ha_open, ha_close)
 *   ha_low[t]   = min(low,  ha_open, ha_close)
 * </pre>
 */
public final class HeikinAshiCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal TWO = new BigDecimal(2);
    private static final BigDecimal FOUR = new BigDecimal(4);

    private HeikinAshiCalculator() {}

    /** Compute one HA bar from the previous HA (optional) and the current OHLC. */
    public static HABar compute(Optional<HABar> prev, OHLCBar ohlc, Instant computedAt) {
        BigDecimal haClose = ohlc.open()
                .add(ohlc.high(), MC)
                .add(ohlc.low(), MC)
                .add(ohlc.close(), MC)
                .divide(FOUR, MC);

        BigDecimal haOpen = prev.map(p -> p.haOpen().add(p.haClose(), MC).divide(TWO, MC))
                .orElseGet(() -> ohlc.open().add(ohlc.close(), MC).divide(TWO, MC));

        BigDecimal haHigh = max(max(ohlc.high(), haOpen), haClose);
        BigDecimal haLow = min(min(ohlc.low(), haOpen), haClose);

        return new HABar(
                ohlc.instrumentId(), ohlc.timeframe(), ohlc.barTime(), haOpen, haHigh, haLow, haClose, computedAt);
    }

    /**
     * Compute a chain of HA bars. {@code prev} is the HA bar immediately
     * preceding the first OHLC (may be empty for a fresh seed). {@code ohlcs}
     * must be sorted ascending by {@code bar_time}.
     */
    public static List<HABar> computeChain(Optional<HABar> prev, List<OHLCBar> ohlcs, Instant computedAt) {
        List<HABar> out = new ArrayList<>(ohlcs.size());
        Optional<HABar> running = prev;
        for (OHLCBar ohlc : ohlcs) {
            HABar ha = compute(running, ohlc, computedAt);
            out.add(ha);
            running = Optional.of(ha);
        }
        return out;
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
