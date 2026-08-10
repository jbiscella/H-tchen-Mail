package com.heikinashi.monitoring.infrastructure.chart;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.ChartRenderer;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.infrastructure.hatrack.CommonsBarAdapter;
import jakarta.inject.Singleton;
import java.util.List;
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
        return HaLookbackWindow.forEvent(haRepository, event, config.getLookbackBars());
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
        // Resolved through the shared helper so the AI analyst's technical-context block
        // (Block 18) describes exactly the overlays drawn here — one source of truth.
        List<Indicator> indicators = ConfiguredChartIndicators.derive(config);

        for (ChartIndicatorPlacement.Placed p : ChartIndicatorPlacement.drawn(indicators, bars)) {
            if (p.pane() == Pane.MAIN) {
                builder.addIndicator(p.indicator());
            } else {
                builder.addIndicator(p.indicator(), p.pane());
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
