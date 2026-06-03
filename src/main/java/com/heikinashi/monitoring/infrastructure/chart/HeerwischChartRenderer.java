package com.heikinashi.monitoring.infrastructure.chart;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.ChartRenderer;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.infrastructure.hatrack.CommonsBarAdapter;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hatrack.commons.PriceSource;
import org.hatrack.heerwisch.api.error.DriverInternalException;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.ChartSpec;
import org.hatrack.heerwisch.api.spec.ChartSpecBuilder;
import org.hatrack.heerwisch.api.spec.ImageFormat;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.hatrack.heerwisch.api.spec.LayoutSpec;
import org.hatrack.heerwisch.api.spec.Pane;
import org.hatrack.heerwisch.jfreechart.JFreeChartRenderer;

/**
 * Block 14 — {@link ChartRenderer} backed by ha-track's heerwisch
 * ({@code heerwisch-api} {@code ChartSpec}/{@code ChartRenderer}, rendered by the
 * headless {@code heerwisch-jfreechart} driver), replacing the custom
 * JFreeChart renderer so chart output is shared with wichtelm-app.
 *
 * <p>The HA lookback window is converted to a commons {@code HASeries} through
 * {@link CommonsBarAdapter} (the single domain&lt;-&gt;commons boundary), and the
 * triggering candle is highlighted with an {@link Annotation.BarHighlight} at
 * {@code (bar_time, ha_close)}. heerwisch returns its own {@code ChartImage}
 * (PNG bytes); we map it to the domain {@link com.heikinashi.monitoring.domain.ChartImage}
 * unchanged, so the email MIME structure (multipart text+HTML, inline image CID)
 * is untouched — only the image producer changes.
 *
 * <p>The chart-building idioms mirror wichtelm-app's {@code HtmlReportGenerator}
 * for a consistent house style across the two heerwisch consumers: an
 * interface-typed renderer built via {@link #newRenderer()}, a
 * {@code LayoutSpec.builder()} layout, and a collect-then-place indicator loop
 * that honours each indicator's default pane, assigns the rest to subplot slots,
 * de-duplicates, and skips any whose period exceeds the window. The heerwisch
 * driver is headless and deterministic (embedded DejaVu Sans), so no display or
 * server is required in the Lambda environment.
 */
@Singleton
public class HeerwischChartRenderer implements ChartRenderer {

    // HA charts read the Heikin-Ashi close (wichtelm charts raw OHLC and uses CLOSE).
    private static final PriceSource SOURCE = PriceSource.HA_CLOSE;

    private final HaRepository haRepository;
    private final ChartConfig config;
    private final org.hatrack.heerwisch.api.port.ChartRenderer renderer;

    public HeerwischChartRenderer(HaRepository haRepository, ChartConfig config) {
        this.haRepository = haRepository;
        this.config = config;
        this.renderer = newRenderer();
    }

    @Override
    public ChartImage renderChart(PatternEvent event) {
        try {
            List<HABar> bars = fetchLookback(event);
            ChartSpec spec = buildSpec(event, bars);
            org.hatrack.heerwisch.api.spec.ChartImage image = renderer.render(spec);
            return new ChartImage(image.bytes(), image.contentType(), image.widthPx(), image.heightPx());
        } catch (org.hatrack.heerwisch.api.error.ChartRenderException | RuntimeException e) {
            throw new ChartRenderException(e);
        }
    }

    private List<HABar> fetchLookback(PatternEvent event) {
        Instant cutoff = event.barTime().plusNanos(1);
        List<HABar> bars = new ArrayList<>(haRepository.findLastNBefore(
                event.instrumentId(), event.timeframe(), cutoff, config.getLookbackBars()));
        // Under SNAPSHOT_ONLY a later ingest can delete the triggering HA bar
        // before a queued alert is retried, so the lookback may no longer contain
        // event.barTime(). The BarHighlight must point at a bar that is in the
        // series (heerwisch V7), so synthesize the triggering bar from the event's
        // snapshot when it is missing.
        boolean hasEventBar = bars.stream().anyMatch(b -> b.barTime().equals(event.barTime()));
        if (!hasEventBar) {
            bars.add(new HABar(
                    event.instrumentId(),
                    event.timeframe(),
                    event.barTime(),
                    event.barSnapshot().haOpen(),
                    event.barSnapshot().haHigh(),
                    event.barSnapshot().haLow(),
                    event.barSnapshot().haClose(),
                    event.detectedAt()));
        }
        // heerwisch requires strictly-ascending, unique bar times (V3/V4).
        bars.sort(Comparator.comparing(HABar::barTime));
        return bars;
    }

    private ChartSpec buildSpec(PatternEvent event, List<HABar> bars)
            throws org.hatrack.heerwisch.api.error.ChartRenderException {
        LayoutSpec layout = LayoutSpec.builder()
                .withSize(config.getWidthPx(), config.getHeightPx())
                .withFormat(ImageFormat.PNG)
                .build();
        // Highlight the detected candle at (bar_time, ha_close). Its time is part
        // of the lookback window, satisfying heerwisch's V7 (highlight on a real bar).
        Annotation.BarHighlight highlight = new Annotation.BarHighlight(
                event.barTime(), event.barSnapshot().haClose(), event.subtype().wire());
        ChartSpecBuilder builder = ChartSpec.builder()
                .withSeries(CommonsBarAdapter.toCommonsHaSeries(bars))
                .withLayout(layout)
                .addAnnotation(highlight);
        addIndicators(builder, bars.size());
        return builder.build();
    }

    /**
     * Overlay the configured indicators, mirroring wichtelm-app's placement: each
     * indicator goes to its own default pane ({@code MAIN} overlays in place;
     * oscillators cycle through the subplot slots), duplicates are collapsed, and
     * any whose period exceeds the window is skipped (heerwisch rejects those with
     * V6, and a short bootstrap chart should still render with fewer overlays).
     */
    private void addIndicators(ChartSpecBuilder builder, int bars) {
        List<Indicator> indicators = new ArrayList<>();
        if (config.getSmaPeriod() > 0) {
            indicators.add(new Indicator.SMA(config.getSmaPeriod(), SOURCE));
        }
        if (config.getEmaPeriod() > 0) {
            indicators.add(new Indicator.EMA(config.getEmaPeriod(), SOURCE));
        }
        if (config.isShowRsi()) {
            indicators.add(new Indicator.RSI(
                    config.getRsiPeriod(),
                    new BigDecimal("70"),
                    new BigDecimal("30"),
                    SOURCE,
                    Optional.of(Indicator.RsiVisualization.DANGER_ZONES_ON)));
        }

        Pane[] subPanes = {
            Pane.SUBPLOT_1, Pane.SUBPLOT_2, Pane.SUBPLOT_3, Pane.SUBPLOT_4,
            Pane.SUBPLOT_5, Pane.SUBPLOT_6, Pane.SUBPLOT_7, Pane.SUBPLOT_8
        };
        int subPaneIdx = 0;
        Set<String> seen = new HashSet<>();
        for (Indicator indicator : indicators) {
            if (bars < indicator.minBars() || !seen.add(indicator.toString())) {
                continue;
            }
            if (indicator.defaultPane() == Pane.MAIN) {
                builder.addIndicator(indicator);
            } else if (subPaneIdx < subPanes.length) {
                builder.addIndicator(indicator, subPanes[subPaneIdx]);
                subPaneIdx++;
            }
        }
    }

    private static org.hatrack.heerwisch.api.port.ChartRenderer newRenderer() {
        try {
            return new JFreeChartRenderer();
        } catch (DriverInternalException e) {
            // Font/driver init failure — surface as the domain render error.
            throw new ChartRenderException(e);
        }
    }
}
