package com.heikinashi.monitoring.infrastructure.bedrock;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaLookbackWindow;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.infrastructure.chart.ChartConfig;
import com.heikinashi.monitoring.infrastructure.chart.ChartIndicatorPlacement;
import com.heikinashi.monitoring.infrastructure.chart.ConfiguredChartIndicators;
import com.heikinashi.monitoring.infrastructure.chart.StrategyChartIndicators;
import com.heikinashi.monitoring.infrastructure.hatrack.CommonsBarAdapter;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.PriceSource;
import org.hatrack.dsl.BarIndicatorSource;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block 18 Part A — the technical-context block appended to the AI analyst's user
 * message: the same series the alert chart draws, plus the value of every indicator the
 * chart overlays.
 *
 * <p>Before this, the analyst received eight numbers (the alert bar's four HA values and
 * four raw OHLC values) and nothing else — no trend, no indicator levels — while the
 * chart rendered beside it carried a lookback window, moving averages and an RSI
 * sub-pane. Notes consequently hedged about context the reader could see ("presumably
 * trading near the $195-$200 band") and never once cited an RSI value across a 15-email
 * sample, despite RSI being plotted in all 15.
 *
 * <p><strong>The two flows are not the same chart, so the basis differs per flow.</strong>
 * Making it uniform was the P1 defect in the first version of this class:
 *
 * <ul>
 *   <li>{@link PatternEvent} → HA candles. Series is the {@code HABar} window from
 *       {@link HaLookbackWindow}; indicators evaluate on {@code ha_close}, matching the
 *       pattern chart's {@link PriceSource#HA_CLOSE} overlays.</li>
 *   <li>{@link StrategyAlert} → <em>raw</em> OHLC. {@code StrategyChartSpec} builds a
 *       commons OHLC series and {@link StrategyChartIndicators} uses
 *       {@link PriceSource#CLOSE}, so the series is the raw {@code OHLCBar} window the
 *       caller charted and indicators evaluate on the raw close.</li>
 * </ul>
 *
 * Serving a strategy alert from the HA series would print values that disagree with both
 * the attached strategy chart and the DSL condition that fired the alert — the exact
 * failure this block exists to prevent.
 *
 * <p><strong>Nothing about a rendered artefact is looked up here; it is passed in.</strong>
 * The strategy flow receives both the {@link Strategy} and its bar window as parameters,
 * so this class holds no {@code StrategyRepository} and no {@code OhlcRepository}. Two
 * upstream repairs make a fresh read non-equivalent to what was drawn, and neither is
 * reproducible by reading again: {@code MonitoringRunService} merges freshly-ingested bars
 * over the repository read (which can lag its own write), and
 * {@code StrategyRetryPollerService.withTriggerBar} splices back a trigger bar that
 * retention evicted before the retry ran. Re-deriving either the strategy or the series
 * would narrow those races rather than remove them.
 *
 * <p>The indicator set is likewise resolved per flow through the same helper the
 * corresponding renderer uses ({@link ConfiguredChartIndicators} from
 * {@code monitoring.chart}, or {@link StrategyChartIndicators} from the strategy's
 * conditions), never hardcoded here, and then filtered through
 * {@link ChartIndicatorPlacement} so only indicators the chart has room to draw are
 * described.
 *
 * <p>Values come from {@code dsl-eval}'s {@link BarIndicatorSource}, already on the
 * classpath for strategy evaluation, so no new dependency.
 */
@Singleton
public class TechnicalContextBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(TechnicalContextBuilder.class);

    /** How many trailing values of each indicator to show, so direction is readable, not just level. */
    private static final int PATH_POINTS = 5;

    private final ChartConfig chartConfig;

    public TechnicalContextBuilder(ChartConfig chartConfig) {
        this.chartConfig = chartConfig;
    }

    /**
     * Context for a pattern alert: HA series, {@code ha_close} basis, chart indicators from
     * {@code monitoring.chart}, over the window the <em>caller</em> resolved
     * ({@link HaLookbackWindow#forEvent}) and drew the chart from. Not re-read here: sharing
     * the helper shared only the algorithm, so two calls could still disagree — including on
     * whether the {@code SNAPSHOT_ONLY}-evicted triggering bar exists.
     */
    public String forPatternEvent(PatternEvent event, List<HABar> bars) {
        return render(
                "Heikin-Ashi bars",
                haRows(bars),
                anchorLines(
                        bars.stream()
                                .map(b -> new Anchor(b.barTime(), b.haClose()))
                                .toList(),
                        "ha_close"),
                toHaCloseBars(bars),
                ConfiguredChartIndicators.derive(chartConfig),
                event.timeframe(),
                bars.size());
    }

    /**
     * Context for a strategy alert: raw OHLC series, raw-close basis, indicators derived
     * from the strategy the <em>caller</em> rendered the chart from, over the <em>same</em>
     * bar window it rendered from.
     *
     * <p>Both are parameters rather than repository lookups on purpose (see the class
     * comment): a re-import could swap the strategy between dispatch and this call, and the
     * caller's bar list carries repairs — a fresh-bar merge, or a spliced-in trigger bar —
     * that a fresh read does not reproduce. Passing what was drawn removes both races
     * instead of narrowing them.
     */
    public String forStrategyAlert(
            StrategyAlert alert, Strategy strategy, List<com.heikinashi.monitoring.domain.OHLCBar> bars) {
        return strategyContext(alert, StrategyChartIndicators.derive(strategy), bars);
    }

    /**
     * Degraded variant: the strategy was deleted since detection, so the send is already
     * chart-degraded. The series still carries useful price action; the indicator set is a
     * property of the strategy, so there is none to report.
     */
    public String forStrategyAlert(StrategyAlert alert, List<com.heikinashi.monitoring.domain.OHLCBar> bars) {
        return strategyContext(alert, List.of(), bars);
    }

    private String strategyContext(
            StrategyAlert alert, List<Indicator> indicators, List<com.heikinashi.monitoring.domain.OHLCBar> bars) {
        if (bars.isEmpty()) {
            return "";
        }
        // Compute over the whole passed window (long periods need it); print only the tail.
        // The chart loads 300 bars for exactly this reason, and its own skip rule was
        // evaluated against this same size — so the two cannot disagree about what is warm.
        int shown = Math.min(bars.size(), chartConfig.getLookbackBars());
        List<com.heikinashi.monitoring.domain.OHLCBar> tail = bars.subList(bars.size() - shown, bars.size());
        return render(
                "raw OHLC bars",
                ohlcRows(tail),
                anchorLines(
                        tail.stream()
                                .map(b -> new Anchor(b.barTime(), b.close()))
                                .toList(),
                        "close"),
                toRawBars(bars),
                indicators,
                alert.timeframe(),
                shown);
    }

    private String render(
            String seriesLabel,
            List<String> rows,
            List<String> anchors,
            List<OHLCBar> forEvaluation,
            List<Indicator> indicators,
            Timeframe tf,
            int shown) {
        if (shown == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Chart context — the same series the attached chart draws (")
                .append(shown)
                .append(" ")
                .append(seriesLabel)
                .append(" on ")
                .append(tf.wire())
                .append(", oldest first):\n");
        rows.forEach(r -> sb.append("  ").append(r).append("\n"));
        if (!anchors.isEmpty()) {
            sb.append("\nWindow anchors (pre-computed, so they need not be read off the rows above):\n");
            anchors.forEach(a -> sb.append("  ").append(a).append("\n"));
        }
        appendIndicators(sb, forEvaluation, indicators);
        return sb.toString();
    }

    /** Shortest display scale tried — price series settle here. */
    private static final int MIN_SCALE = 2;

    /** Longest fixed scale tried; past this, shortening is abandoned rather than forced. */
    private static final int MAX_SCALE = 8;

    /**
     * How one series is printed: a fixed number of decimals, or {@code exact} when no fixed
     * scale can shorten it without destroying a distinction.
     *
     * <p>The {@code exact} arm exists because a bounded search needs an answer for "the bound
     * was exhausted". The first version returned {@code MAX_SCALE} unconditionally, so values
     * differing only past 8 dp — {@code 0.000000004} against {@code -0.000000004} — printed
     * identically as {@code 0.00000000} and a MACD crossing vanished again (Codex P2 on PR
     * #87, the second finding on this formatter). Raising the cap would only move that
     * boundary; refusing to shorten removes it. Brevity is a nicety here, fidelity is not.
     */
    private record Precision(int scale, boolean verbatim) {

        static Precision fixed(int scale) {
            return new Precision(scale, false);
        }

        /** Component is {@code verbatim}, not {@code exact}: a record accessor cannot share a
         * name with a static factory. */
        static Precision exact() {
            return new Precision(0, true);
        }

        /**
         * Part A.2 — the first live note drifted "roughly 52.2" out of a row reading ha_close
         * 51.385 alongside ha_open 51.79760900267785, and full-precision BigDecimals are
         * hostile to a model reproducing them in prose. Display only: indicators are still
         * computed on the unrounded series, so this can never move a value.
         *
         * <p>{@code toPlainString} throughout, including the exact arm: {@code toString} would
         * render a round number like 100 as {@code 1E+2}.
         */
        String show(BigDecimal v) {
            return verbatim
                    ? v.stripTrailingZeros().toPlainString()
                    : v.setScale(scale, RoundingMode.HALF_UP).toPlainString();
        }
    }

    /**
     * Whether {@code scale} would flatten a non-zero value to zero — which destroys its sign.
     * A flat 2 dp printed a MACD histogram of {@code -0.0021} as {@code -0.00} (Codex P2 on
     * PR #87): the zero cross the alert keyed on disappears, and a negative zero misleads.
     */
    private static boolean flattensToZero(BigDecimal v, int scale) {
        return v.signum() != 0 && v.setScale(scale, RoundingMode.HALF_UP).signum() == 0;
    }

    /** Whether two values that genuinely differ would display identically at {@code scale}. */
    private static boolean collapses(BigDecimal a, BigDecimal b, int scale) {
        return a.compareTo(b) != 0
                && Precision.fixed(scale).show(a).equals(Precision.fixed(scale).show(b));
    }

    /**
     * The shortest safe way to print a series: the smallest scale in
     * {@code [MIN_SCALE, MAX_SCALE]} — the bound <b>inclusive</b> — at which every value keeps
     * its sign and no {@code mustDiffer} pair collapses, or {@link Precision#exact()} when no
     * such scale exists. One precision per series, so columns stay aligned.
     *
     * <p>A fixed 2 dp was the first implementation and it is a <em>price</em>-shaped format:
     * correct for a €57 instrument, destructive for a small-magnitude indicator or a low-priced
     * one. Choosing from the data keeps the brevity where it is safe and drops it where it is
     * not — including when the whole range is unsafe, which the first bounded version silently
     * treated as success.
     */
    private static Precision precisionFor(List<BigDecimal> values, List<BigDecimal[]> mustDiffer) {
        for (int scale = MIN_SCALE; scale <= MAX_SCALE; scale++) {
            boolean ok = true;
            for (BigDecimal v : values) {
                if (flattensToZero(v, scale)) {
                    ok = false;
                    break;
                }
            }
            for (BigDecimal[] pair : mustDiffer) {
                if (!ok) {
                    break;
                }
                if (collapses(pair[0], pair[1], scale)) {
                    ok = false;
                }
            }
            if (ok) {
                return Precision.fixed(scale);
            }
        }
        return Precision.exact();
    }

    /** A bar reduced to what the anchors need. */
    private record Anchor(Instant time, BigDecimal close) {}

    /**
     * The quantities the model would otherwise derive by scanning the grid — and did derive
     * wrongly on the first live alert ("from X on 30 June", "trough near 48.4 on 23 July",
     * "rallied ~18% from trough"). Pre-computing them is the Part A.2 lesson: labelled values
     * survive into the note intact, grid-extracted ones drift.
     */
    private static List<String> anchorLines(List<Anchor> series, String closeLabel) {
        if (series.size() < 2) {
            return List.of();
        }
        Anchor first = series.get(0);
        Anchor last = series.get(series.size() - 1);
        Anchor low = series.stream().min(Comparator.comparing(Anchor::close)).orElseThrow();
        Anchor high = series.stream().max(Comparator.comparing(Anchor::close)).orElseThrow();
        // Anchors are quoted as levels, so they must not collapse into each other either: a
        // window whose low and high printed the same number would assert a flat series.
        Precision precision = precisionFor(
                series.stream().map(Anchor::close).toList(),
                List.of(new BigDecimal[] {low.close(), high.close()}, new BigDecimal[] {first.close(), last.close()}));
        List<String> lines = new ArrayList<>(5);
        lines.add("window first %s = %s on %s".formatted(closeLabel, precision.show(first.close()), first.time()));
        lines.add("lowest %s = %s on %s".formatted(closeLabel, precision.show(low.close()), low.time()));
        lines.add("highest %s = %s on %s".formatted(closeLabel, precision.show(high.close()), high.time()));
        lines.add("change from lowest %s to alert bar = %s%%".formatted(closeLabel, pct(low.close(), last.close())));
        lines.add("change across window = %s%%".formatted(pct(first.close(), last.close())));
        return lines;
    }

    /**
     * Signed percentage change from {@code from} to {@code to}. Scaled so a small but non-zero
     * move is not reported as {@code 0.00%} — a flat move and a 0.004% move are different
     * claims about the window.
     */
    private static String pct(BigDecimal from, BigDecimal to) {
        if (from.signum() == 0) {
            return "n/a";
        }
        BigDecimal change =
                to.subtract(from).divide(from, java.math.MathContext.DECIMAL64).multiply(BigDecimal.valueOf(100));
        String sign = change.signum() > 0 ? "+" : "";
        return sign + precisionFor(List.of(change), List.of()).show(change);
    }

    /**
     * Display scale for a bar series. {@code open} vs {@code close} is the pair that must
     * survive: {@link #colourOf} reads the candle's colour from full precision, so a scale
     * that printed them equal would produce a row contradicting its own colour label (Codex P2
     * on PR #87 — {@code ha_open 1.001} / {@code ha_close 1.004} both became {@code 1.00}).
     */
    private static Precision barScale(List<BigDecimal[]> openClosePairs, List<BigDecimal> allValues) {
        return precisionFor(allValues, openClosePairs);
    }

    private static List<String> haRows(List<HABar> bars) {
        List<BigDecimal> values = new ArrayList<>(bars.size() * 4);
        List<BigDecimal[]> pairs = new ArrayList<>(bars.size());
        for (HABar b : bars) {
            values.add(b.haOpen());
            values.add(b.haHigh());
            values.add(b.haLow());
            values.add(b.haClose());
            pairs.add(new BigDecimal[] {b.haOpen(), b.haClose()});
        }
        Precision precision = barScale(pairs, values);
        List<String> rows = new ArrayList<>(bars.size() + 1);
        rows.add("bar_time  ha_open  ha_high  ha_low  ha_close  colour");
        for (HABar b : bars) {
            rows.add("%s  %s  %s  %s  %s  %s"
                    .formatted(
                            b.barTime(),
                            precision.show(b.haOpen()),
                            precision.show(b.haHigh()),
                            precision.show(b.haLow()),
                            precision.show(b.haClose()),
                            colourOf(b)));
        }
        return rows;
    }

    private static List<String> ohlcRows(List<com.heikinashi.monitoring.domain.OHLCBar> bars) {
        List<BigDecimal> values = new ArrayList<>(bars.size() * 4);
        List<BigDecimal[]> pairs = new ArrayList<>(bars.size());
        for (com.heikinashi.monitoring.domain.OHLCBar b : bars) {
            values.add(b.open());
            values.add(b.high());
            values.add(b.low());
            values.add(b.close());
            // No colour label on a raw row, but an open printed equal to a close it differs
            // from would still misrepresent the bar's direction.
            pairs.add(new BigDecimal[] {b.open(), b.close()});
        }
        Precision precision = barScale(pairs, values);
        List<String> rows = new ArrayList<>(bars.size() + 1);
        rows.add("bar_time  open  high  low  close");
        for (com.heikinashi.monitoring.domain.OHLCBar b : bars) {
            rows.add("%s  %s  %s  %s  %s"
                    .formatted(
                            b.barTime(),
                            precision.show(b.open()),
                            precision.show(b.high()),
                            precision.show(b.low()),
                            precision.show(b.close())));
        }
        return rows;
    }

    /** HA candle colour, the same rule the chart paints by. */
    private static String colourOf(HABar bar) {
        int cmp = bar.haClose().compareTo(bar.haOpen());
        return cmp > 0 ? "green" : cmp < 0 ? "red" : "flat";
    }

    private void appendIndicators(StringBuilder sb, List<OHLCBar> bars, List<Indicator> indicators) {
        // Describe only what the chart draws, decided by the renderers' own placement rules
        // via the shared helper: the window skip (heerwisch V6), dedup, and the eight
        // subplot slots. Reimplementing any of them here is how the note ends up citing a
        // pane the reader cannot see — the ninth oscillator has nowhere to be drawn.
        List<Indicator> drawn = ChartIndicatorPlacement.drawn(indicators, bars.size()).stream()
                .map(ChartIndicatorPlacement.Placed::indicator)
                .toList();
        if (drawn.size() < indicators.size()) {
            LOG.debug(
                    "technical_context_indicators_not_drawn resolved={} drawn={} bars={}",
                    indicators.size(),
                    drawn.size(),
                    bars.size());
        }
        List<String> lines = new ArrayList<>();
        for (Indicator indicator : drawn) {
            for (DslCall call : dslCalls(indicator)) {
                line(bars, call).ifPresent(lines::add);
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\nIndicator values at the alert bar, on the same price basis as the drawn")
                .append(" overlays (oldest→newest path in brackets):\n");
        lines.forEach(l -> sb.append("  ").append(l).append("\n"));
    }

    /**
     * One indicator line: value at the alert bar plus a short trailing path, so the model
     * can read direction as well as level. A value that cannot be computed (indicator not
     * warm) is omitted rather than reported as zero.
     */
    private Optional<String> line(List<OHLCBar> bars, DslCall call) {
        List<BigDecimal> path = new ArrayList<>();
        // minBars - 1 is the zero-based index of the first bar at which the indicator is
        // warm. Using minBars itself omitted the indicator whenever the window was exactly
        // its minimum (e.g. SMA(30) on a 30-bar lookback) — which both charts do draw.
        int firstWarm = Math.max(0, call.minBars() - 1);
        int from = Math.max(firstWarm, bars.size() - PATH_POINTS);
        for (int i = from; i < bars.size(); i++) {
            valueAt(bars, i, call).ifPresent(path::add);
        }
        if (path.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal latest = path.get(path.size() - 1);
        // Scale chosen from the path itself. A flat 2 dp is a price-shaped format: it printed
        // a MACD histogram of 0.0034 / -0.0021 as 0.00 / -0.00, erasing the very zero cross a
        // macd_* alert fires on, and it can flatten two adjacent points into one another so the
        // path shows no direction (Codex P2 on PR #87).
        List<BigDecimal[]> adjacent = new ArrayList<>(Math.max(0, path.size() - 1));
        for (int i = 1; i < path.size(); i++) {
            adjacent.add(new BigDecimal[] {path.get(i - 1), path.get(i)});
        }
        Precision precision = precisionFor(path, adjacent);
        StringBuilder line = new StringBuilder(call.label()).append(" = ").append(precision.show(latest));
        if (path.size() > 1) {
            line.append("  [");
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(precision.show(path.get(i)));
            }
            line.append("]");
        }
        return Optional.of(line.toString());
    }

    private Optional<BigDecimal> valueAt(List<OHLCBar> bars, int index, DslCall call) {
        List<OHLCBar> upTo = bars.subList(0, index + 1);
        OHLCBar at = bars.get(index);
        try {
            BarIndicatorSource source = new BarIndicatorSource(upTo, "", at.time(), index);
            return Optional.ofNullable(source.evaluate(call.name(), call.args()));
        } catch (RuntimeException e) {
            // Not warm yet, or dsl-eval does not expose this indicator — omit the point.
            return Optional.empty();
        }
    }

    /**
     * The HA window as commons bars whose close is {@code ha_close}, so evaluated values
     * match the pattern chart's {@code PriceSource.HA_CLOSE} overlays.
     */
    private static List<OHLCBar> toHaCloseBars(List<HABar> bars) {
        List<OHLCBar> out = new ArrayList<>(bars.size());
        for (HABar bar : bars) {
            out.add(new OHLCBar(
                    bar.barTime(), bar.haOpen(), bar.haHigh(), bar.haLow(), bar.haClose(), Optional.empty()));
        }
        return out;
    }

    /**
     * The raw window as commons bars, matching the strategy chart's
     * {@code PriceSource.CLOSE}. Routed through {@link CommonsBarAdapter} — the single
     * domain&lt;-&gt;commons boundary (Block 11) — rather than converting inline: the
     * hand-rolled version this replaces also dropped {@code volume}, which the adapter
     * passes through and a volume-based indicator would need.
     */
    private static List<OHLCBar> toRawBars(List<com.heikinashi.monitoring.domain.OHLCBar> bars) {
        return bars.stream().map(CommonsBarAdapter::toCommons).toList();
    }

    /**
     * A heerwisch chart indicator mapped to the {@code dsl-eval} function that computes
     * it. An indicator with no numeric equivalent yields empty and is simply not described.
     */
    private static List<DslCall> dslCalls(Indicator indicator) {
        return switch (indicator) {
            case Indicator.SMA sma -> List.of(DslCall.of("sma", "SMA(" + sma.period() + ")", sma.period()));
            case Indicator.EMA ema -> List.of(DslCall.of("ema", "EMA(" + ema.period() + ")", ema.period()));
            case Indicator.RSI rsi -> List.of(DslCall.of("rsi", "RSI(" + rsi.period() + ")", rsi.period()));
            case Indicator.ATR atr -> List.of(DslCall.of("atr", "ATR(" + atr.period() + ")", atr.period()));
            case Indicator.StdDev sd -> List.of(DslCall.of("stddev", "StdDev(" + sd.period() + ")", sd.period()));
            // All three MACD components, because the sub-pane draws all three. Reporting
            // only macd_line described a third of what the reader can see, and the line
            // alone cannot express the cross the strategy may have keyed on.
            case Indicator.MACD macd -> {
                String args = macd.fastPeriod() + "," + macd.slowPeriod() + "," + macd.signalPeriod();
                List<BigDecimal> periods = List.of(
                        BigDecimal.valueOf(macd.fastPeriod()),
                        BigDecimal.valueOf(macd.slowPeriod()),
                        BigDecimal.valueOf(macd.signalPeriod()));
                yield List.of(
                        new DslCall("macd_line", "MACD line(" + args + ")", periods, macd.slowPeriod()),
                        new DslCall("macd_signal", "MACD signal(" + args + ")", periods, macd.slowPeriod()),
                        new DslCall("macd_histogram", "MACD histogram(" + args + ")", periods, macd.slowPeriod()));
            }
            case Indicator.RollingMax max ->
                List.of(DslCall.of(
                        max.priceSource() == PriceSource.HIGH ? "highest_high" : "highest_close",
                        "HHV(" + max.period() + ")",
                        max.period()));
            case Indicator.RollingMin min ->
                List.of(DslCall.of(
                        min.priceSource() == PriceSource.LOW ? "lowest_low" : "lowest_close",
                        "LLV(" + min.period() + ")",
                        min.period()));
            default -> List.of();
        };
    }

    /** A dsl-eval function call plus the label the note should use for it. */
    private record DslCall(String name, String label, List<BigDecimal> args, int minBars) {
        static DslCall of(String name, String label, int period) {
            return new DslCall(name, label, List.of(BigDecimal.valueOf(period)), period);
        }
    }
}
