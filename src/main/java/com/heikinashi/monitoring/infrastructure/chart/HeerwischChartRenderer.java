package com.heikinashi.monitoring.infrastructure.chart;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.ChartRenderer;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.infrastructure.hatrack.CommonsBarAdapter;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.ChartSpec;
import org.hatrack.heerwisch.api.spec.ImageFormat;
import org.hatrack.heerwisch.api.spec.LayoutSpec;
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
 * <p>The heerwisch driver is headless and deterministic (embedded DejaVu Sans),
 * so no display or server is required in the Lambda environment.
 */
@Singleton
public class HeerwischChartRenderer implements ChartRenderer {

    private final HaRepository haRepository;
    private final ChartConfig config;
    private final JFreeChartRenderer renderer;

    public HeerwischChartRenderer(HaRepository haRepository, ChartConfig config) {
        this.haRepository = haRepository;
        this.config = config;
        try {
            this.renderer = new JFreeChartRenderer();
        } catch (org.hatrack.heerwisch.api.error.ChartRenderException e) {
            // Font/driver init failure — surface as the domain render error.
            throw new ChartRenderException(e);
        }
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
        // heerwisch requires strictly-ascending, unique bar times (V3/V4).
        bars.sort(Comparator.comparing(HABar::barTime));
        return bars;
    }

    private ChartSpec buildSpec(PatternEvent event, List<HABar> bars)
            throws org.hatrack.heerwisch.api.error.ChartRenderException {
        LayoutSpec layout = new LayoutSpec.AutoLayoutSpec(config.getWidthPx(), config.getHeightPx(), ImageFormat.PNG);
        // Highlight the detected candle at (bar_time, ha_close). Its time is part
        // of the lookback window, satisfying heerwisch's V7 (highlight on a real bar).
        Annotation.BarHighlight highlight = new Annotation.BarHighlight(
                event.barTime(), event.barSnapshot().haClose(), event.subtype().wire());
        return ChartSpec.builder()
                .withSeries(CommonsBarAdapter.toCommonsHaSeries(bars))
                .withLayout(layout)
                .addAnnotation(highlight)
                .build();
    }
}
