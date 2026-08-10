package com.heikinashi.monitoring.infrastructure.bedrock;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyRepository;
import com.heikinashi.monitoring.infrastructure.chart.ChartConfig;
import com.heikinashi.monitoring.infrastructure.chart.ConfiguredChartIndicators;
import com.heikinashi.monitoring.infrastructure.chart.StrategyChartIndicators;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
 * chart rendered beside it carried a 30-bar lookback, moving averages and an RSI
 * sub-pane. Notes consequently hedged about context the reader could see ("presumably
 * trading near the $195–$200 band") and never once cited an RSI value across a 15-email
 * sample, despite RSI being plotted in all 15.
 *
 * <p><strong>One source of truth.</strong> The window is loaded through the same
 * {@link HaRepository#findLastNBefore} call and {@code lookback-bars} setting the
 * renderer uses, and the indicator set is resolved through the same helper — per flow:
 *
 * <ul>
 *   <li>{@link PatternEvent} → {@link ConfiguredChartIndicators#derive(ChartConfig)}
 *       (whatever {@code monitoring.chart} enables).</li>
 *   <li>{@link StrategyAlert} → {@link StrategyChartIndicators#derive} over the
 *       instrument's strategy (whatever its conditions reference).</li>
 * </ul>
 *
 * The set is never hardcoded here: an operator who disables the RSI sub-pane, or a
 * strategy that references MACD instead of SMA, changes both the image and this block
 * together.
 *
 * <p><strong>Why the HA close.</strong> Indicator values come from {@code dsl-eval}'s
 * {@link BarIndicatorSource}, which computes over a bar's close. The chart computes its
 * overlays over {@link PriceSource#HA_CLOSE}, so the window is mapped to commons bars
 * with {@code ha_close} as the close before evaluation. A raw-close basis would print
 * numbers that disagree with the lines in the image beside them — the exact failure this
 * block exists to prevent.
 */
@Singleton
public class TechnicalContextBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(TechnicalContextBuilder.class);

    /** How many trailing values of each indicator to show, so direction is readable, not just level. */
    private static final int PATH_POINTS = 5;

    private final HaRepository haRepository;
    private final ChartConfig chartConfig;
    private final StrategyRepository strategyRepository;

    public TechnicalContextBuilder(
            HaRepository haRepository, ChartConfig chartConfig, StrategyRepository strategyRepository) {
        this.haRepository = haRepository;
        this.chartConfig = chartConfig;
        this.strategyRepository = strategyRepository;
    }

    /** Context for a pattern alert: chart indicators come from {@code monitoring.chart}. */
    public String forPatternEvent(PatternEvent event) {
        return render(
                window(event.instrumentId(), event.timeframe(), event.barTime()),
                ConfiguredChartIndicators.derive(chartConfig),
                event.timeframe());
    }

    /**
     * Context for a strategy alert: chart indicators come from the instrument's strategy,
     * matching the strategy-driven overlays. A missing strategy yields the bar series with
     * no indicators rather than an error — the series alone is already more than this flow
     * carried before, which was no bar values at all.
     */
    public String forStrategyAlert(StrategyAlert alert) {
        List<Indicator> indicators = strategyRepository
                .findByInstrumentId(alert.instrumentId())
                .map(StrategyChartIndicators::derive)
                .orElseGet(List::of);
        return render(window(alert.instrumentId(), alert.timeframe(), alert.barTime()), indicators, alert.timeframe());
    }

    /** The chart's lookback window: same repository call, same cutoff, same bar count. */
    private List<HABar> window(String instrumentId, Timeframe tf, Instant barTime) {
        return haRepository.findLastNBefore(instrumentId, tf, barTime.plusNanos(1), chartConfig.getLookbackBars());
    }

    private String render(List<HABar> bars, List<Indicator> indicators, Timeframe tf) {
        if (bars.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Chart context — the same series the attached chart draws (")
                .append(bars.size())
                .append(" Heikin-Ashi bars on ")
                .append(tf.wire())
                .append(", oldest first):\n");
        sb.append("  bar_time  ha_open  ha_high  ha_low  ha_close  colour\n");
        for (HABar bar : bars) {
            sb.append("  ")
                    .append(bar.barTime())
                    .append("  ")
                    .append(bar.haOpen())
                    .append("  ")
                    .append(bar.haHigh())
                    .append("  ")
                    .append(bar.haLow())
                    .append("  ")
                    .append(bar.haClose())
                    .append("  ")
                    .append(colourOf(bar))
                    .append("\n");
        }
        appendIndicators(sb, bars, indicators);
        return sb.toString();
    }

    /** HA candle colour, the same rule the chart paints by. */
    private static String colourOf(HABar bar) {
        int cmp = bar.haClose().compareTo(bar.haOpen());
        return cmp > 0 ? "green" : cmp < 0 ? "red" : "flat";
    }

    private void appendIndicators(StringBuilder sb, List<HABar> bars, List<Indicator> indicators) {
        List<OHLCBar> commonsBars = toHaCloseBars(bars);
        List<String> lines = new ArrayList<>();
        for (Indicator indicator : indicators) {
            // Honour the renderer's skip rule (heerwisch V6): an indicator whose period
            // exceeds the window is not drawn, so it must not be described either.
            if (bars.size() < indicator.minBars()) {
                LOG.debug("technical_context_skipping_indicator reason=window_too_short indicator={}", indicator);
                continue;
            }
            dslCall(indicator).ifPresent(call -> line(commonsBars, call).ifPresent(lines::add));
        }
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\nIndicator values at the alert bar, computed on the Heikin-Ashi close so they")
                .append(" match the overlays drawn on the chart (oldest→newest path in brackets):\n");
        lines.forEach(l -> sb.append("  ").append(l).append("\n"));
    }

    /**
     * One indicator line: value at the alert bar plus a short trailing path, so the model
     * can read direction as well as level. A value that cannot be computed (indicator not
     * warm) is omitted rather than reported as zero.
     */
    private Optional<String> line(List<OHLCBar> bars, DslCall call) {
        List<BigDecimal> path = new ArrayList<>();
        int from = Math.max(call.minBars(), bars.size() - PATH_POINTS);
        for (int i = from; i < bars.size(); i++) {
            valueAt(bars, i, call).ifPresent(path::add);
        }
        if (path.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal latest = path.get(path.size() - 1);
        StringBuilder line = new StringBuilder(call.label()).append(" = ").append(latest);
        if (path.size() > 1) {
            line.append("  [");
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(path.get(i));
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

    /** The HA window as commons bars whose close is {@code ha_close} (see class javadoc). */
    private static List<OHLCBar> toHaCloseBars(List<HABar> bars) {
        List<OHLCBar> out = new ArrayList<>(bars.size());
        for (HABar bar : bars) {
            out.add(new OHLCBar(
                    bar.barTime(), bar.haOpen(), bar.haHigh(), bar.haLow(), bar.haClose(), Optional.empty()));
        }
        return out;
    }

    /**
     * A heerwisch chart indicator mapped to the {@code dsl-eval} function that computes
     * it. An indicator with no numeric equivalent yields empty and is simply not described.
     */
    private static Optional<DslCall> dslCall(Indicator indicator) {
        return switch (indicator) {
            case Indicator.SMA sma -> Optional.of(DslCall.of("sma", "SMA(" + sma.period() + ")", sma.period()));
            case Indicator.EMA ema -> Optional.of(DslCall.of("ema", "EMA(" + ema.period() + ")", ema.period()));
            case Indicator.RSI rsi -> Optional.of(DslCall.of("rsi", "RSI(" + rsi.period() + ")", rsi.period()));
            case Indicator.ATR atr -> Optional.of(DslCall.of("atr", "ATR(" + atr.period() + ")", atr.period()));
            case Indicator.StdDev sd -> Optional.of(DslCall.of("stddev", "StdDev(" + sd.period() + ")", sd.period()));
            case Indicator.MACD macd -> {
                String label = "MACD(" + macd.fastPeriod() + "," + macd.slowPeriod() + "," + macd.signalPeriod() + ")";
                yield Optional.of(new DslCall(
                        "macd_line",
                        label,
                        List.of(
                                BigDecimal.valueOf(macd.fastPeriod()),
                                BigDecimal.valueOf(macd.slowPeriod()),
                                BigDecimal.valueOf(macd.signalPeriod())),
                        macd.slowPeriod()));
            }
            case Indicator.RollingMax max ->
                Optional.of(DslCall.of(
                        max.priceSource() == PriceSource.HIGH ? "highest_high" : "highest_close",
                        "HHV(" + max.period() + ")",
                        max.period()));
            case Indicator.RollingMin min ->
                Optional.of(DslCall.of(
                        min.priceSource() == PriceSource.LOW ? "lowest_low" : "lowest_close",
                        "LLV(" + min.period() + ")",
                        min.period()));
            default -> Optional.empty();
        };
    }

    /** A dsl-eval function call plus the label the note should use for it. */
    private record DslCall(String name, String label, List<BigDecimal> args, int minBars) {
        static DslCall of(String name, String label, int period) {
            return new DslCall(name, label, List.of(BigDecimal.valueOf(period)), period);
        }
    }
}
