package com.heikinashi.monitoring.infrastructure.chart;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import com.heikinashi.monitoring.infrastructure.hatrack.CommonsBarAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hatrack.heerwisch.api.error.InvalidChartSpecException;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.CandleStyle;
import org.hatrack.heerwisch.api.spec.ChartSpec;
import org.hatrack.heerwisch.api.spec.ChartSpecBuilder;
import org.hatrack.heerwisch.api.spec.GlyphStyle;
import org.hatrack.heerwisch.api.spec.ImageFormat;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.hatrack.heerwisch.api.spec.LayoutSpec;
import org.hatrack.heerwisch.api.spec.MarkerDirection;
import org.hatrack.heerwisch.api.spec.Pane;

/**
 * SI-2 — builds the heerwisch {@link ChartSpec} for a {@link StrategyAlert}: the
 * strategy's derived overlays (SI-1, via {@link StrategyChartIndicators}) placed
 * in their panes, plus a role-derived direction marker on the matched bar
 * (CLAUDE.md §9 Component 1b). Pure: strategy + alert + the raw OHLC lookback bars
 * in, a {@code ChartSpec} out — no DB, no PNG rendering. Candles are Heikin-Ashi,
 * drawn from the raw series by {@code CandleStyle.HEIKIN_ASHI} (ha-track 0.57),
 * while indicators read the raw close (§9 Component 1b).
 */
public final class StrategyChartSpec {

    private StrategyChartSpec() {}

    public static ChartSpec build(Strategy strategy, StrategyAlert alert, List<OHLCBar> bars, ChartConfig config) {
        try {
            LayoutSpec layout = LayoutSpec.builder()
                    .withSize(config.getWidthPx(), config.getHeightPx())
                    .withFormat(ImageFormat.PNG)
                    .build();
            ChartSpecBuilder builder = ChartSpec.builder()
                    .withSeries(CommonsBarAdapter.toCommonsOhlcSeries(bars))
                    .withCandleStyle(CandleStyle.HEIKIN_ASHI)
                    .withLayout(layout);
            placeIndicators(builder, StrategyChartIndicators.derive(strategy), bars.size());
            addMarkers(builder, alert, bars);
            return builder.build();
        } catch (InvalidChartSpecException e) {
            throw new ChartRenderException(e);
        }
    }

    /**
     * Each indicator goes to its {@code defaultPane()} (MAIN overlays in place;
     * oscillators cycle the subplot slots), de-duplicated, and any whose minimum
     * window exceeds the available bars is skipped. The rules themselves live in
     * {@link ChartIndicatorPlacement}, shared with the legacy
     * {@link HeerwischChartRenderer} and with the AI analyst's technical-context
     * block so all three agree on what is drawn (Block 18).
     */
    private static void placeIndicators(ChartSpecBuilder builder, List<Indicator> indicators, int bars) {
        for (ChartIndicatorPlacement.Placed p : ChartIndicatorPlacement.drawn(indicators, bars)) {
            if (p.pane() == Pane.MAIN) {
                builder.addIndicator(p.indicator());
            } else {
                builder.addIndicator(p.indicator(), p.pane());
            }
        }
    }

    /**
     * One marker per matched line's role on the alert bar (de-duplicated by role).
     * Recognized roles map to a direction marker per CLAUDE.md §9 Component 1b;
     * an unrecognized role falls back to a neutral bar highlight. Display only —
     * the role text is never used for a trade decision.
     */
    private static void addMarkers(ChartSpecBuilder builder, StrategyAlert alert, List<OHLCBar> bars) {
        Instant at = alert.barTime();
        Set<String> seenRoles = new HashSet<>();
        for (StrategyAlertLine line : alert.lines()) {
            if (!seenRoles.add(line.role())) {
                continue;
            }
            switch (line.role()) {
                case "long_entry" ->
                    builder.addAnnotation(
                            new Annotation.EntryExitMarkerAuto(at, MarkerDirection.LONG_ENTRY, GlyphStyle.UP_TRIANGLE));
                case "short_entry" ->
                    builder.addAnnotation(new Annotation.EntryExitMarkerAuto(
                            at, MarkerDirection.SHORT_ENTRY, GlyphStyle.DOWN_TRIANGLE));
                case "long_exit" ->
                    builder.addAnnotation(new Annotation.EntryExitMarkerAuto(
                            at, MarkerDirection.LONG_EXIT, GlyphStyle.DOWN_TRIANGLE));
                case "short_exit" ->
                    builder.addAnnotation(
                            new Annotation.EntryExitMarkerAuto(at, MarkerDirection.SHORT_EXIT, GlyphStyle.UP_TRIANGLE));
                default -> builder.addAnnotation(new Annotation.BarHighlight(at, closeAt(bars, at), line.role()));
            }
        }
    }

    private static BigDecimal closeAt(List<OHLCBar> bars, Instant at) {
        return bars.stream()
                .filter(b -> b.barTime().equals(at))
                .map(OHLCBar::close)
                .findFirst()
                .orElseGet(() -> bars.isEmpty()
                        ? BigDecimal.ZERO
                        : bars.get(bars.size() - 1).close());
    }
}
