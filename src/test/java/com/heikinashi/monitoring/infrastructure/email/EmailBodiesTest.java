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
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
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
        assertThat(html).contains("INSTRUMENT");
        assertThat(html).contains("color_change &middot; bullish_reversal");
        assertThat(html).contains("// corroborating");
        assertThat(html).contains("Earnings momentum supports the signal.");
        assertThat(html).contains("FUNDAMENTAL CONFIDENCE");
        assertThat(html).contains("MEDIUM");
        assertThat(html).contains("enrichment full");
    }

    @Test
    void html_body_carries_the_legal_disclaimer_footer_in_quiet_terminal_style() {
        String full = EmailBodies.html(EVENT, Optional.of("img-1"), Optional.of(ANALYSIS), AlertEnrichment.FULL);
        String degraded = EmailBodies.html(EVENT, Optional.empty(), Optional.empty(), AlertEnrichment.DEGRADED_BOTH);
        for (String html : new String[] {full, degraded}) {
            // Substance: the four legal clauses must still be present.
            assertThat(html)
                    .contains("disclaimer &middot;")
                    .contains("automated alert from historical pattern detection")
                    .contains("not financial advice")
                    .contains("past performance not indicative")
                    .contains("provided AS IS under open-source license")
                    .contains("no warranties");
            // Style: no shouty bold or caps — it must read as part of the
            // technical footer, not as a separate warning block.
            assertThat(html)
                    .doesNotContain("<strong>Disclaimer:")
                    .doesNotContain("NOT financial advice")
                    .doesNotContain("<footer");
        }
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

    // --- Strategy-alert bodies (SI-3c) ---------------------------------------

    private static final StrategyAlert STRATEGY_ALERT = new StrategyAlert(
            "abc-123",
            "AAPL",
            "NASDAQ",
            Timeframe.D1,
            Instant.parse("2026-05-06T00:00:00Z"),
            "rsi-reversal-long",
            List.of(
                    new StrategyAlertLine(
                            "oversold-entry",
                            "long_entry",
                            Optional.of("no open position"), // positionPrecondition
                            Optional.of("95.00"), // stopLoss
                            Optional.of("120.00")), // takeProfit
                    new StrategyAlertLine(
                            "trend-exit", "long_exit", Optional.empty(), Optional.empty(), Optional.empty())),
            Instant.parse("2026-05-07T22:00:00Z"));

    @Test
    void strategy_subject_follows_the_canonical_template() {
        String subject = EmailBodies.subject("[HA Alert]", STRATEGY_ALERT);
        assertThat(subject).isEqualTo("[HA Alert] AAPL.NASDAQ — strategy rsi-reversal-long on 1d (2026-05-06)");
    }

    @Test
    void strategy_plain_text_includes_strategy_note_and_AI_when_present() {
        String text = EmailBodies.plainText(STRATEGY_ALERT, Optional.of(ANALYSIS));
        assertThat(text).contains("AI fundamental analysis (confidence: MEDIUM)");
        assertThat(text).contains("Corroborating: Earnings momentum supports the signal.");
        assertThat(text).contains("Contradicting: Sector beta is high.");
    }

    @Test
    void strategy_plain_text_omits_AI_block_when_absent() {
        String text = EmailBodies.plainText(STRATEGY_ALERT, Optional.empty());
        assertThat(text).doesNotContain("AI fundamental analysis");
    }

    @Test
    void strategy_html_with_chart_and_AI_renders_matched_scenarios_and_memo() {
        String html = EmailBodies.html(STRATEGY_ALERT, Optional.of("chart-9"), Optional.of(ANALYSIS));
        assertThat(html).contains("<img src=\"cid:chart-9\"");
        assertThat(html).contains("Strategy alert.");
        assertThat(html).contains("rsi-reversal-long");
        assertThat(html).contains("STRATEGY");
        assertThat(html).contains("matched 2 scenario(s)");
        // matched-scenario lines + verbatim memo (Block 16).
        assertThat(html).contains("oversold-entry [long_entry]");
        assertThat(html).contains("stop_loss: 95.00 (set this at your broker)");
        assertThat(html).contains("take_profit: 120.00 (set this at your broker)");
        assertThat(html).contains("applies only if: no open position (context)");
        assertThat(html).contains("// corroborating");
        assertThat(html).contains("Earnings momentum supports the signal.");
        // legal footer carries through on the strategy path too.
        assertThat(html).contains("not financial advice").contains("no warranties");
    }

    @Test
    void strategy_html_without_chart_or_AI_is_degraded() {
        String html = EmailBodies.html(STRATEGY_ALERT, Optional.empty(), Optional.empty());
        assertThat(html).doesNotContain("<img");
        assertThat(html).contains("// fundamental analysis");
        assertThat(html).contains("AI fundamental analysis unavailable for this alert.");
        assertThat(html).contains("oversold-entry [long_entry]");
    }

    @Test
    void strategy_html_escapes_hostile_strategy_name() {
        StrategyAlert hostile = new StrategyAlert(
                "abc-123",
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                Instant.parse("2026-05-06T00:00:00Z"),
                "<script>evil()</script>",
                List.of(new StrategyAlertLine("s", "long_entry", Optional.empty(), Optional.empty(), Optional.empty())),
                Instant.parse("2026-05-07T22:00:00Z"));
        String html = EmailBodies.html(hostile, Optional.empty(), Optional.empty());
        assertThat(html).doesNotContain("<script>evil()");
        assertThat(html).contains("&lt;script&gt;");
    }
}
