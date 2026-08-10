package com.heikinashi.monitoring.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.application.InMemoryHaRepository;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiConfidence;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.infrastructure.chart.ChartConfig;
import com.heikinashi.monitoring.infrastructure.chart.HeerwischChartRenderer;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Renders a full alert email (HTML) with the heerwisch chart inlined as a data
 * URI, so the result is a standalone, browser-viewable sample. Emits
 * target/sample-alert-email.html for visual validation.
 */
class SampleAlertEmailRenderTest {

    private static final String INSTR = "abc-123";
    private static final Instant T0 = Instant.parse("2026-04-14T00:00:00Z");

    private HABar ha(int day, double open, double close) {
        BigDecimal o = bd(open);
        BigDecimal c = bd(close);
        BigDecimal hi = o.max(c).add(new BigDecimal("1.20"));
        BigDecimal lo = o.min(c).subtract(new BigDecimal("1.10"));
        return new HABar(INSTR, Timeframe.D1, T0.plus(day, ChronoUnit.DAYS), o, hi, lo, c, T0);
    }

    private static BigDecimal bd(double v) {
        return new BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Test
    void renders_a_standalone_sample_alert_email() throws IOException {
        InMemoryHaRepository haRepo = new InMemoryHaRepository();
        // ~40 bars of a wavy uptrend so SMA/EMA/RSI overlays are meaningful,
        // ending on a green bar after a short red streak (a bullish reversal).
        double price = 95;
        HABar last = null;
        for (int d = 0; d < 40; d++) {
            double drift = 0.25;
            double wave = 3.0 * Math.sin(d / 3.5);
            double open = price;
            double close = 95 + drift * d + wave + (d >= 37 ? (d - 36) * 1.3 : 0);
            HABar bar = ha(d, open, close);
            haRepo.putBar(bar, Optional.empty());
            price = close;
            last = bar;
        }

        BarSnapshot snap = new BarSnapshot(
                bd(98.5),
                bd(103.1),
                bd(97.9),
                bd(102.6),
                Optional.of(new BigDecimal("1843200")),
                last.haOpen(),
                last.haHigh(),
                last.haLow(),
                last.haClose());
        PatternEvent event = new PatternEvent(
                INSTR,
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                last.barTime(),
                PatternKind.COLOR_CHANGE,
                PatternSubtype.BULLISH_REVERSAL,
                Map.of("min_streak_length", 3),
                snap,
                Instant.parse("2026-05-07T22:00:00Z"));

        AiAnalysis ai = new AiAnalysis(
                Optional.of("The bullish Heikin-Ashi reversal lines up with a constructive tape: a multi-day "
                        + "down-streak exhausting on rising volume, and recent coverage skews positive on "
                        + "the name's near-term demand. Momentum turning while volume expands is the kind of "
                        + "confirmation this pattern wants."),
                Optional.of("Valuation remains rich versus peers and the broader sector has been choppy, so a single "
                        + "reversal bar is not a trend. No earnings catalyst is imminent, which limits "
                        + "follow-through conviction."),
                AiConfidence.MEDIUM,
                List.of("news_headlines(5)", "recommendations(0)", "quote_info(1)"));

        ChartConfig config = new ChartConfig();
        config.setLookbackBars(30);
        config.setWidthPx(900);
        config.setHeightPx(500);
        byte[] png = new HeerwischChartRenderer(config)
                .renderChart(
                        event,
                        com.heikinashi.monitoring.domain.HaLookbackWindow.forEvent(
                                haRepo, event, config.getLookbackBars()))
                .bytes();

        String cid = "chart.png";
        String html = EmailBodies.html(event, Optional.of(cid), Optional.of(ai), AlertEnrichment.FULL);
        // Inline the chart so the HTML is viewable standalone (real emails use the CID).
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        html = html.replace("cid:" + cid, dataUri);

        assertThat(html).contains("Heikin-Ashi pattern detected.");
        assertThat(html).contains("AAPL.NASDAQ");
        assertThat(html).contains(dataUri);
        assertThat(html).contains("disclaimer");

        Path out = Path.of("target", "sample-alert-email.html");
        Files.createDirectories(out.getParent());
        Files.write(out, html.getBytes(StandardCharsets.UTF_8));
    }
}
