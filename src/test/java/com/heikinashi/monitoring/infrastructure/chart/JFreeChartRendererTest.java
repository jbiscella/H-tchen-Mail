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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JFreeChartRendererTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    void renders_a_PNG_for_a_pattern_event() {
        InMemoryHaRepository ha = new InMemoryHaRepository();
        Instant base = Instant.parse("2026-04-01T00:00:00Z");
        for (int i = 0; i < 30; i++) {
            BigDecimal mid = new BigDecimal(100 + i);
            HABar bar = new HABar(
                    "abc-123",
                    Timeframe.D1,
                    base.plusSeconds(i * 86_400L),
                    mid,
                    mid.add(new BigDecimal("2")),
                    mid.subtract(new BigDecimal("1")),
                    mid.add(new BigDecimal("1")),
                    base);
            ha.putBar(bar, Optional.empty());
        }

        ChartConfig config = new ChartConfig();
        config.setLookbackBars(30);
        config.setWidthPx(640);
        config.setHeightPx(360);
        JFreeChartRenderer renderer = new JFreeChartRenderer(ha, config);

        Instant patternBar = base.plusSeconds(29 * 86_400L);
        PatternEvent event = new PatternEvent(
                "abc-123",
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                patternBar,
                PatternKind.COLOR_CHANGE,
                PatternSubtype.BULLISH_REVERSAL,
                Map.of("min_streak_length", 3),
                new BarSnapshot(
                        new BigDecimal("129"),
                        new BigDecimal("131"),
                        new BigDecimal("128"),
                        new BigDecimal("130"),
                        Optional.empty(),
                        new BigDecimal("129"),
                        new BigDecimal("131"),
                        new BigDecimal("128"),
                        new BigDecimal("130")),
                Instant.parse("2026-05-07T22:00:00Z"));

        ChartImage image = renderer.renderChart(event);

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.widthPx()).isEqualTo(640);
        assertThat(image.heightPx()).isEqualTo(360);
        assertThat(image.bytes()).isNotEmpty();
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            assertThat(image.bytes()[i]).as("PNG magic byte %d", i).isEqualTo(PNG_MAGIC[i]);
        }
    }
}
