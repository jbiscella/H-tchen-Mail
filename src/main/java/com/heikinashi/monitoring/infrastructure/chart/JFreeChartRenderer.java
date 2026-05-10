package com.heikinashi.monitoring.infrastructure.chart;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.ChartRenderer;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import jakarta.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.data.time.FixedMillisecond;
import org.jfree.data.time.ohlc.OHLCSeries;
import org.jfree.data.time.ohlc.OHLCSeriesCollection;

/**
 * {@link ChartRenderer} backed by JFreeChart (CLAUDE.md §9).
 *
 * <p>HA values are passed to {@link CandlestickRenderer} as if they were the
 * native OHLC tuple — the spec's pragmatic way of reusing the standard
 * candlestick rendering for HA candles. The detected pattern bar is
 * highlighted with an {@link XYPointerAnnotation} arrow at
 * {@code (bar_time, ha_close)}.
 *
 * <p>Renders entirely in memory (no filesystem). The constructor sets
 * {@code java.awt.headless=true} before any AWT class is touched.
 */
@Singleton
public class JFreeChartRenderer implements ChartRenderer {

    private final HaRepository haRepository;
    private final ChartConfig config;

    public JFreeChartRenderer(HaRepository haRepository, ChartConfig config) {
        // Must run before any AWT class is loaded. Lambda also sets this, but we
        // hard-set it here for local / unit-test contexts.
        System.setProperty("java.awt.headless", "true");
        this.haRepository = haRepository;
        this.config = config;
    }

    @Override
    public ChartImage renderChart(PatternEvent event) {
        try {
            List<HABar> bars = fetchLookback(event);
            JFreeChart chart = buildChart(event, bars);
            byte[] png = renderToPng(chart);
            return new ChartImage(png, "image/png", config.getWidthPx(), config.getHeightPx());
        } catch (RuntimeException | IOException e) {
            throw new ChartRenderException(e);
        }
    }

    private List<HABar> fetchLookback(PatternEvent event) {
        Instant cutoff = event.barTime().plusNanos(1);
        return haRepository.findLastNBefore(event.instrumentId(), event.timeframe(), cutoff, config.getLookbackBars());
    }

    private JFreeChart buildChart(PatternEvent event, List<HABar> bars) {
        OHLCSeries series = new OHLCSeries(event.ticker());
        for (HABar bar : bars) {
            series.add(
                    new FixedMillisecond(Date.from(bar.barTime())),
                    bar.haOpen().doubleValue(),
                    bar.haHigh().doubleValue(),
                    bar.haLow().doubleValue(),
                    bar.haClose().doubleValue());
        }
        OHLCSeriesCollection dataset = new OHLCSeriesCollection();
        dataset.addSeries(series);

        JFreeChart chart = ChartFactory.createCandlestickChart(title(event), "Date", "Price (HA)", dataset, false);

        XYPlot plot = chart.getXYPlot();
        if (plot.getRenderer() instanceof CandlestickRenderer cr) {
            cr.setUpPaint(new Color(0x2E, 0xCC, 0x71));
            cr.setDownPaint(new Color(0xE7, 0x4C, 0x3C));
            cr.setUseOutlinePaint(false);
        }

        // Highlight the pattern bar with an arrow at (bar_time, ha_close).
        XYPointerAnnotation arrow = new XYPointerAnnotation(
                event.subtype().wire(),
                Date.from(event.barTime()).getTime(),
                event.barSnapshot().haClose().doubleValue(),
                Math.toRadians(225));
        arrow.setTipRadius(8.0);
        arrow.setBaseRadius(40.0);
        arrow.setFont(new Font("SansSerif", Font.BOLD, 12));
        arrow.setPaint(Color.DARK_GRAY);
        arrow.setArrowPaint(Color.DARK_GRAY);
        arrow.setArrowStroke(new BasicStroke(1.5f));
        plot.addAnnotation(arrow);

        return chart;
    }

    private byte[] renderToPng(JFreeChart chart) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ChartUtils.writeChartAsPNG(out, chart, config.getWidthPx(), config.getHeightPx());
            return out.toByteArray();
        }
    }

    private static String title(PatternEvent event) {
        return event.ticker()
                + "."
                + event.exchange()
                + "  "
                + event.pattern().wire()
                + "/"
                + event.subtype().wire()
                + "  "
                + event.timeframe().wire()
                + "  "
                + event.barTime().toString().substring(0, 10);
    }
}
