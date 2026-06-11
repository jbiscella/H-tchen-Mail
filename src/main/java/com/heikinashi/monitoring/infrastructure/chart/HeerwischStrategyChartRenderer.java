package com.heikinashi.monitoring.infrastructure.chart;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.StrategyChartRenderer;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import jakarta.inject.Singleton;
import java.util.List;
import org.hatrack.heerwisch.api.error.DriverInternalException;
import org.hatrack.heerwisch.api.spec.ChartSpec;
import org.hatrack.heerwisch.jfreechart.JFreeChartRenderer;

/**
 * SI-3c.1 — {@link StrategyChartRenderer} backed by ha-track's heerwisch: builds
 * the {@link StrategyChartSpec} (SI-2) and renders it to PNG via the headless
 * {@code heerwisch-jfreechart} driver, mapping the heerwisch image to the domain
 * {@link ChartImage} (CLAUDE.md §9 Component 1b). Same driver and house style as
 * the legacy {@link HeerwischChartRenderer}; only the spec source differs.
 */
@Singleton
public class HeerwischStrategyChartRenderer implements StrategyChartRenderer {

    private final ChartConfig config;
    private final org.hatrack.heerwisch.api.port.ChartRenderer renderer;

    public HeerwischStrategyChartRenderer(ChartConfig config) {
        this.config = config;
        this.renderer = newRenderer();
    }

    @Override
    public ChartImage render(StrategyAlert alert, Strategy strategy, List<OHLCBar> bars) {
        try {
            ChartSpec spec = StrategyChartSpec.build(strategy, alert, bars, config);
            org.hatrack.heerwisch.api.spec.ChartImage image = renderer.render(spec);
            return new ChartImage(image.bytes(), image.contentType(), image.widthPx(), image.heightPx());
        } catch (org.hatrack.heerwisch.api.error.ChartRenderException | RuntimeException e) {
            // Wrap ANY render fault (heerwisch's checked ChartRenderException, or an
            // unchecked heerwisch/JFreeChart RuntimeException) as the domain
            // ChartRenderException, so the dispatch chart-stage handler enqueues /
            // degrades it instead of the fault escaping and failing the whole
            // instrument run. Same contract as the legacy HeerwischChartRenderer.
            throw new ChartRenderException(e);
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
