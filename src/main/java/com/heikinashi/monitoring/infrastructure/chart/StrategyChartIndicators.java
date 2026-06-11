package com.heikinashi.monitoring.infrastructure.chart;

import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hatrack.commons.PriceSource;
import org.hatrack.heerwisch.api.spec.Indicator;

/**
 * SI-1 — derives the heerwisch chart overlays from a {@link Strategy}'s scenario
 * conditions, so the alert chart shows ONLY the indicators the rule references
 * (mirroring wichtelm-app's {@code HtmlReportGenerator} derivation, adapted to
 * H-tchen's Heikin-Ashi charts).
 *
 * <p>The derivation is <b>exclusive</b>: an indicator appears on the chart only
 * when a scenario condition references it. HA primitives ({@code ha_doji},
 * {@code ha_bullish_reversal}, …) contribute no overlay — they are already the HA
 * candles. Duplicates (the same indicator referenced by several scenarios) are
 * collapsed by {@code indicator.toString()}, matching wichtelm's dedup.
 *
 * <p>Like wichtelm, indicator references are found by scanning the condition
 * STRINGS for {@code name(args)} calls (ha-track's {@code dsl-eval} exposes no
 * referenced-indicator API) and mapped to {@code Indicator.*} via a fixed table.
 * Price source is the raw {@code CLOSE} (NOT {@code HA_CLOSE}): the rule engine
 * ({@code DslConditionEvaluator}) evaluates these indicators on raw OHLC, so the
 * overlays must read the same raw price or the chart would contradict the alert.
 * Candles stay Heikin-Ashi via {@code CandleStyle.HEIKIN_ASHI} (ha-track 0.57),
 * which draws HA candle bodies from the raw series while indicators read raw close.
 *
 * <p>Pure and deterministic: the only input is the parsed strategy; no I/O.
 */
public final class StrategyChartIndicators {

    /** Raw close: overlays read the same raw price the rule engine evaluates on, not HA. */
    private static final PriceSource SOURCE = PriceSource.CLOSE;

    private static final Optional<Indicator.RsiVisualization> RSI_VIZ =
            Optional.of(Indicator.RsiVisualization.DANGER_ZONES_ON);
    private static final BigDecimal DEFAULT_OVERBOUGHT = new BigDecimal("70");
    private static final BigDecimal DEFAULT_OVERSOLD = new BigDecimal("30");

    // Default MACD periods for the parameterless Tier-B macd cross primitives.
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    // Default RSI period for the Tier-B rsi_* primitives (which carry no period).
    private static final int RSI_DEFAULT_PERIOD = 14;

    /** Matches a DSL function call {@code name(args)} anywhere in a condition string. */
    private static final Pattern CALL = Pattern.compile("([a-z_][a-z0-9_]*)\\s*\\(([^)]*)\\)");

    private StrategyChartIndicators() {}

    /**
     * The heerwisch indicators referenced by the strategy's scenario conditions,
     * de-duplicated, in first-seen order. Pane placement is left to the caller via
     * each {@link Indicator}'s {@code defaultPane()}.
     */
    public static List<Indicator> derive(Strategy strategy) {
        Map<String, Indicator> byKey = new LinkedHashMap<>();

        // Tier-B rsi_* primitives carry no period and may split overbought /
        // oversold across separate conditions, so they thread into a single RSI.
        boolean rsiTierB = false;
        BigDecimal overbought = DEFAULT_OVERBOUGHT;
        BigDecimal oversold = DEFAULT_OVERSOLD;

        for (StrategyScenario scenario : strategy.scenarios()) {
            for (String condition : scenario.conditions()) {
                Matcher call = CALL.matcher(condition);
                while (call.find()) {
                    String name = call.group(1);
                    String[] args = splitArgs(call.group(2));
                    switch (name) {
                        case "rsi" ->
                            put(
                                    byKey,
                                    new Indicator.RSI(
                                            intArg(args, 0), DEFAULT_OVERBOUGHT, DEFAULT_OVERSOLD, SOURCE, RSI_VIZ));
                        case "sma" -> put(byKey, new Indicator.SMA(intArg(args, 0), SOURCE));
                        case "ema" -> put(byKey, new Indicator.EMA(intArg(args, 0), SOURCE));
                        case "atr" -> put(byKey, new Indicator.ATR(intArg(args, 0)));
                        case "stddev" -> put(byKey, new Indicator.StdDev(intArg(args, 0), SOURCE));
                        case "macd_line", "macd_signal", "macd_histogram" ->
                            put(byKey, new Indicator.MACD(intArg(args, 0), intArg(args, 1), intArg(args, 2), SOURCE));
                        case "highest_high" -> put(byKey, new Indicator.RollingMax(intArg(args, 0), PriceSource.HIGH));
                        case "lowest_low" -> put(byKey, new Indicator.RollingMin(intArg(args, 0), PriceSource.LOW));
                        case "highest_close" -> put(byKey, new Indicator.RollingMax(intArg(args, 0), SOURCE));
                        case "lowest_close" -> put(byKey, new Indicator.RollingMin(intArg(args, 0), SOURCE));
                        case "macd_bullish_cross", "macd_bearish_cross", "macd_zero_cross_up", "macd_zero_cross_down" ->
                            put(byKey, new Indicator.MACD(MACD_FAST, MACD_SLOW, MACD_SIGNAL, SOURCE));
                        case "rsi_crosses_50" -> rsiTierB = true;
                        case "rsi_overbought" -> {
                            rsiTierB = true;
                            overbought = decArg(args, 0, overbought);
                        }
                        case "rsi_oversold" -> {
                            rsiTierB = true;
                            oversold = decArg(args, 0, oversold);
                        }
                        case "price_above_sma",
                                "price_below_sma",
                                "price_crosses_above_sma",
                                "price_crosses_below_sma" -> put(byKey, new Indicator.SMA(intArg(args, 0), SOURCE));
                        case "price_above_ema",
                                "price_below_ema",
                                "price_crosses_above_ema",
                                "price_crosses_below_ema" -> put(byKey, new Indicator.EMA(intArg(args, 0), SOURCE));
                        case "sma_above_ema", "sma_crosses_above_ema", "sma_crosses_below_ema" -> {
                            put(byKey, new Indicator.SMA(intArg(args, 0), SOURCE));
                            put(byKey, new Indicator.EMA(intArg(args, 1), SOURCE));
                        }
                        default -> {
                            // ha_* primitives and any non-indicator call: no overlay.
                        }
                    }
                }
            }
        }
        if (rsiTierB) {
            put(byKey, new Indicator.RSI(RSI_DEFAULT_PERIOD, overbought, oversold, SOURCE, RSI_VIZ));
        }
        return List.copyOf(byKey.values());
    }

    private static void put(Map<String, Indicator> byKey, Indicator indicator) {
        byKey.putIfAbsent(indicator.toString(), indicator);
    }

    private static String[] splitArgs(String raw) {
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s*,\\s*");
    }

    private static int intArg(String[] args, int index) {
        return Integer.parseInt(args[index].trim());
    }

    private static BigDecimal decArg(String[] args, int index, BigDecimal fallback) {
        return index < args.length ? new BigDecimal(args[index].trim()) : fallback;
    }
}
