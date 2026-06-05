package com.heikinashi.monitoring.infrastructure.chart;

import com.heikinashi.monitoring.domain.HABar;
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
 * (CLAUDE.md §9 Component 1b). Pure: strategy + alert + the HA lookback bars in,
 * a {@code ChartSpec} out — no DB, no PNG rendering.
 */
public final class StrategyChartSpec {

    private static final Pane[] SUB_PANES = {
        Pane.SUBPLOT_1, Pane.SUBPLOT_2, Pane.SUBPLOT_3, Pane.SUBPLOT_4,
        Pane.SUBPLOT_5, Pane.SUBPLOT_6, Pane.SUBPLOT_7, Pane.SUBPLOT_8
    };

    private StrategyChartSpec() {}

    public static ChartSpec build(Strategy strategy, StrategyAlert alert, List<HABar> bars, ChartConfig config) {
        try {
            LayoutSpec layout = LayoutSpec.builder()
                    .withSize(config.getWidthPx(), config.getHeightPx())
                    .withFormat(ImageFormat.PNG)
                    .build();
            ChartSpecBuilder builder = ChartSpec.builder()
                    .withSeries(CommonsBarAdapter.toCommonsHaSeries(bars))
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
     * window exceeds the available bars is skipped — the same placement loop the
     * legacy {@link HeerwischChartRenderer} uses (heerwisch rejects over-long
     * indicators with V6).
     */
    private static void placeIndicators(ChartSpecBuilder builder, List<Indicator> indicators, int bars) {
        int subPaneIdx = 0;
        Set<String> seen = new HashSet<>();
        for (Indicator indicator : indicators) {
            if (bars < indicator.minBars() || !seen.add(indicator.toString())) {
                continue;
            }
            if (indicator.defaultPane() == Pane.MAIN) {
                builder.addIndicator(indicator);
            } else if (subPaneIdx < SUB_PANES.length) {
                builder.addIndicator(indicator, SUB_PANES[subPaneIdx]);
                subPaneIdx++;
            }
        }
    }

    /**
     * One marker per matched line's role on the alert bar (de-duplicated by role).
     * Recognized roles map to a direction marker per CLAUDE.md §9 Component 1b;
     * an unrecognized role falls back to a neutral bar highlight. Display only —
     * the role text is never used for a trade decision.
     */
    private static void addMarkers(ChartSpecBuilder builder, StrategyAlert alert, List<HABar> bars) {
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
                default -> builder.addAnnotation(new Annotation.BarHighlight(at, haCloseAt(bars, at), line.role()));
            }
        }
    }

    private static BigDecimal haCloseAt(List<HABar> bars, Instant at) {
        return bars.stream()
                .filter(b -> b.barTime().equals(at))
                .map(HABar::haClose)
                .findFirst()
                .orElseGet(() -> bars.isEmpty()
                        ? BigDecimal.ZERO
                        : bars.get(bars.size() - 1).haClose());
    }
}
