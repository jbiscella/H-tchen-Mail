package com.heikinashi.monitoring.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiConfidence;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailBodiesTest {

    private static final PatternEvent EVENT = new PatternEvent(
            "abc-123",
            "AAPL",
            "NASDAQ",
            Timeframe.D1,
            Instant.parse("2026-05-07T00:00:00Z"),
            PatternKind.COLOR_CHANGE,
            PatternSubtype.BULLISH_REVERSAL,
            Map.of("min_streak_length", 3),
            new BarSnapshot(
                    new BigDecimal("100"),
                    new BigDecimal("110"),
                    new BigDecimal("95"),
                    new BigDecimal("105"),
                    Optional.of(new BigDecimal("123456")),
                    new BigDecimal("100"),
                    new BigDecimal("110"),
                    new BigDecimal("95"),
                    new BigDecimal("105")),
            Instant.parse("2026-05-07T22:00:00Z"));

    private static final AiAnalysis ANALYSIS = new AiAnalysis(
            Optional.of("Earnings momentum supports the signal."),
            Optional.of("Sector beta is high."),
            AiConfidence.MEDIUM,
            List.of("quote_info", "news"));

    @Test
    void subject_follows_the_canonical_template() {
        String subject = EmailBodies.subject("[HA Alert]", EVENT);
        assertThat(subject).isEqualTo("[HA Alert] AAPL.NASDAQ — color_change/bullish_reversal on 1d (2026-05-07)");
    }

    @Test
    void plain_text_body_includes_HA_OHLC_volume_and_AI_when_full() {
        String text = EmailBodies.plainText(EVENT, Optional.of(ANALYSIS));
        assertThat(text).contains("Heikin Ashi pattern detected.");
        assertThat(text).contains("ha_open  = 100.00");
        assertThat(text).contains("ha_close = 105.00");
        assertThat(text).contains("open   = 100.00");
        assertThat(text).contains("volume = 123456");
        assertThat(text).contains("AI fundamental analysis (confidence: MEDIUM)");
        assertThat(text).contains("Corroborating: Earnings momentum supports the signal.");
    }

    @Test
    void html_body_with_chart_includes_inline_cid_image_tag() {
        String html = EmailBodies.html(EVENT, Optional.of("img-1"), Optional.of(ANALYSIS), AlertEnrichment.FULL);
        assertThat(html).contains("<img src=\"cid:img-1\"");
        assertThat(html).contains("AAPL.NASDAQ");
        assertThat(html).contains("Heikin-Ashi pattern detected.");
        assertThat(html).contains("--pattern=color_change/bullish_reversal");
        assertThat(html).contains("// corroborating");
        assertThat(html).contains("Earnings momentum supports the signal.");
        assertThat(html).contains("FUNDAMENTAL CONFIDENCE");
        assertThat(html).contains("MEDIUM");
        assertThat(html).contains("enrichment full");
    }

    @Test
    void html_body_without_chart_emits_unavailable_placeholder() {
        String html = EmailBodies.html(EVENT, Optional.empty(), Optional.empty(), AlertEnrichment.DEGRADED_BOTH);
        assertThat(html).doesNotContain("<img");
        assertThat(html).contains("chart unavailable");
        assertThat(html).contains("// fundamental analysis");
        assertThat(html).contains("AI fundamental analysis unavailable for this alert.");
        assertThat(html).contains("enrichment degraded_both");
    }

    @Test
    void html_body_escapes_dynamic_text_from_the_ai_analysis() {
        AiAnalysis hostile = new AiAnalysis(
                Optional.of("danger <script>alert(1)</script> & co"), Optional.empty(), AiConfidence.LOW, List.of());
        String html = EmailBodies.html(EVENT, Optional.of("img-1"), Optional.of(hostile), AlertEnrichment.FULL);
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }
}
