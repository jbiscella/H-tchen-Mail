package com.heikinashi.monitoring.infrastructure.chart;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.application.InMemoryHaRepository;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Block 14 — the alert chart renders via heerwisch and yields a PNG the email composer can embed. */
class HeerwischChartRendererTest {

    private static final String INSTR = "abc-123";
    private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private HABar ha(int day, String o, String h, String l, String c) {
        return new HABar(
                INSTR,
                Timeframe.D1,
                T0.plus(day, ChronoUnit.DAYS),
                new BigDecimal(o),
                new BigDecimal(h),
                new BigDecimal(l),
                new BigDecimal(c),
                Instant.parse("2026-05-07T22:00:00Z"));
    }

    @Test
    void renders_a_png_chart_with_the_triggering_candle_highlighted() throws IOException {
        InMemoryHaRepository haRepo = new InMemoryHaRepository();
        // ~40-bar HA window (a wavy uptrend) so the SMA/EMA overlays and the RSI
        // subplot all have enough bars to render; the last bar is the detected one.
        HABar last = null;
        for (int d = 0; d < 40; d++) {
            double base = 95 + 0.25 * d + 3.0 * Math.sin(d / 3.5);
            double open = 95 + 0.25 * (d - 1) + 3.0 * Math.sin((d - 1) / 3.5);
            double close = base + (d >= 37 ? (d - 36) * 1.2 : 0);
            double high = Math.max(open, close) + 1.2;
            double low = Math.min(open, close) - 1.1;
            HABar bar = ha(d, s(open), s(high), s(low), s(close));
            haRepo.putBar(bar, Optional.empty());
            last = bar;
        }

        ChartConfig config = new ChartConfig();
        config.setLookbackBars(30);
        config.setWidthPx(900);
        config.setHeightPx(500);

        PatternEvent event = new PatternEvent(
                INSTR,
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                last.barTime(),
                PatternKind.COLOR_CHANGE,
                PatternSubtype.BULLISH_REVERSAL,
                java.util.Map.of("min_streak_length", 3),
                new BarSnapshot(
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("95"),
                        new BigDecimal("105"),
                        Optional.of(new BigDecimal("12345")),
                        last.haOpen(),
                        last.haHigh(),
                        last.haLow(),
                        last.haClose()),
                Instant.parse("2026-05-07T22:00:00Z"));

        HeerwischChartRenderer renderer = new HeerwischChartRenderer(haRepo, config);
        ChartImage image = renderer.renderChart(event);

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.widthPx()).isEqualTo(900);
        assertThat(image.heightPx()).isEqualTo(500);
        assertThat(image.bytes()).startsWith(PNG_MAGIC);
        assertThat(image.bytes().length).isGreaterThan(1000);

        // Emit the rendered chart so the increment can be validated visually.
        Path out = Path.of("target", "heerwisch-chart-sample.png");
        Files.createDirectories(out.getParent());
        Files.write(out, image.bytes());
    }

    @Test
    void renders_even_when_the_triggering_bar_is_missing_from_history() {
        // SNAPSHOT_ONLY: the triggering HA bar was deleted by a later ingest before
        // this queued alert was retried, so the lookback is empty.
        InMemoryHaRepository haRepo = new InMemoryHaRepository();
        ChartConfig config = new ChartConfig();
        config.setLookbackBars(30);

        PatternEvent event = new PatternEvent(
                INSTR,
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                T0.plus(5, ChronoUnit.DAYS),
                PatternKind.COLOR_CHANGE,
                PatternSubtype.BULLISH_REVERSAL,
                java.util.Map.of("min_streak_length", 3),
                new BarSnapshot(
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("95"),
                        new BigDecimal("105"),
                        Optional.of(new BigDecimal("12345")),
                        new BigDecimal("101"),
                        new BigDecimal("104"),
                        new BigDecimal("100"),
                        new BigDecimal("103")),
                Instant.parse("2026-05-07T22:00:00Z"));

        // The renderer synthesizes the triggering bar from the snapshot so the
        // highlight still points at a real bar — no ChartRenderException.
        ChartImage image = new HeerwischChartRenderer(haRepo, config).renderChart(event);
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.bytes()).startsWith(PNG_MAGIC);
    }

    private static String s(double v) {
        return new BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
