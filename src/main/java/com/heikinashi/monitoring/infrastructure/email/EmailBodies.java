package com.heikinashi.monitoring.infrastructure.email;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.PatternEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Subject + plain-text + HTML body templates (CLAUDE.md §9). Rendered as
 * static strings; no external templating engine.
 */
final class EmailBodies {

    private EmailBodies() {}

    static String subject(String prefix, PatternEvent event) {
        // [HA Alert] AAPL.NASDAQ — color_change/bullish_reversal on 1d (2026-05-07)
        return prefix
                + " " + event.ticker() + "." + event.exchange()
                + " — " + event.pattern().wire() + "/" + event.subtype().wire()
                + " on " + event.timeframe().wire()
                + " (" + event.barTime().toString().substring(0, 10) + ")";
    }

    static String plainText(PatternEvent event, Optional<AiAnalysis> analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("Heikin Ashi pattern detected.\n\n");
        sb.append("Instrument:   ")
                .append(event.ticker())
                .append(" on ")
                .append(event.exchange())
                .append('\n');
        sb.append("Timeframe:    ").append(event.timeframe().wire()).append('\n');
        sb.append("Bar time:     ").append(event.barTime()).append('\n');
        sb.append("Pattern:      ").append(event.pattern().wire()).append('\n');
        sb.append("Subtype:      ").append(event.subtype().wire()).append("\n\n");

        sb.append("Heikin Ashi values:\n");
        sb.append("  ha_open  = ").append(money(event.barSnapshot().haOpen())).append('\n');
        sb.append("  ha_high  = ").append(money(event.barSnapshot().haHigh())).append('\n');
        sb.append("  ha_low   = ").append(money(event.barSnapshot().haLow())).append('\n');
        sb.append("  ha_close = ").append(money(event.barSnapshot().haClose())).append("\n\n");

        sb.append("Underlying OHLC:\n");
        sb.append("  open   = ").append(money(event.barSnapshot().open())).append('\n');
        sb.append("  high   = ").append(money(event.barSnapshot().high())).append('\n');
        sb.append("  low    = ").append(money(event.barSnapshot().low())).append('\n');
        sb.append("  close  = ").append(money(event.barSnapshot().close())).append('\n');
        event.barSnapshot()
                .volume()
                .ifPresent(v -> sb.append("  volume = ").append(integer(v)).append('\n'));
        sb.append('\n');

        if (!event.paramsUsed().isEmpty()) {
            sb.append("Detection parameters:\n");
            event.paramsUsed()
                    .forEach((k, v) ->
                            sb.append("  ").append(k).append(" = ").append(v).append('\n'));
            sb.append('\n');
        }

        analysis.ifPresent(a -> {
            sb.append("AI fundamental analysis (confidence: ")
                    .append(a.confidence().wire())
                    .append("):\n");
            a.corroborating()
                    .ifPresent(c -> sb.append("  Corroborating: ").append(c).append('\n'));
            a.contradicting()
                    .ifPresent(c -> sb.append("  Contradicting: ").append(c).append('\n'));
            sb.append('\n');
        });

        sb.append("Detected at:   ").append(event.detectedAt()).append('\n');
        sb.append("Instrument id: ").append(event.instrumentId()).append('\n');
        return sb.toString();
    }

    static String html(
            PatternEvent event, Optional<String> chartCid, Optional<AiAnalysis> analysis, AlertEnrichment enrichment) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style=\"font-family: -apple-system, sans-serif; max-width: 600px;\">");
        sb.append("<h2>Heikin Ashi Pattern Detected</h2>");
        sb.append("<p><b>")
                .append(event.ticker())
                .append('.')
                .append(event.exchange())
                .append("</b> — ")
                .append(event.pattern().wire())
                .append(" / ")
                .append(event.subtype().wire())
                .append(" on ")
                .append(event.timeframe().wire())
                .append(" (")
                .append(event.barTime().toString(), 0, 10)
                .append(")</p>");

        if (chartCid.isPresent()) {
            sb.append("<img src=\"cid:")
                    .append(chartCid.get())
                    .append("\" alt=\"Heikin Ashi chart\" style=\"max-width:100%;\">");
        } else {
            sb.append("<p><i>[Chart unavailable] Pattern: ")
                    .append(event.subtype().wire())
                    .append("</i></p>");
        }

        sb.append("<h3>Pattern values</h3>");
        sb.append("<table><tr><td>HA</td><td>");
        sb.append("open=").append(money(event.barSnapshot().haOpen()));
        sb.append(", high=").append(money(event.barSnapshot().haHigh()));
        sb.append(", low=").append(money(event.barSnapshot().haLow()));
        sb.append(", close=").append(money(event.barSnapshot().haClose()));
        sb.append("</td></tr><tr><td>OHLC</td><td>");
        sb.append("open=").append(money(event.barSnapshot().open()));
        sb.append(", high=").append(money(event.barSnapshot().high()));
        sb.append(", low=").append(money(event.barSnapshot().low()));
        sb.append(", close=").append(money(event.barSnapshot().close()));
        sb.append("</td></tr></table>");

        sb.append("<h3>AI fundamental analysis");
        if (analysis.isEmpty()) {
            sb.append(" (unavailable)");
        } else {
            sb.append(" (confidence: ")
                    .append(analysis.get().confidence().wire())
                    .append(')');
        }
        sb.append("</h3>");
        analysis.ifPresent(a -> {
            sb.append("<p><b>Corroborating:</b> ")
                    .append(a.corroborating().orElse(""))
                    .append("</p>");
            sb.append("<p><b>Contradicting:</b> ")
                    .append(a.contradicting().orElse(""))
                    .append("</p>");
            if (!a.dataSources().isEmpty()) {
                sb.append("<p style=\"font-size:11px;color:#888\">Data sources: ");
                sb.append(String.join(", ", a.dataSources()));
                sb.append("</p>");
            }
        });

        sb.append("<hr><p style=\"font-size:11px;color:#888\">");
        sb.append("Detected at ")
                .append(event.detectedAt())
                .append(". Instrument id: ")
                .append(event.instrumentId())
                .append(". Enrichment: ")
                .append(enrichment.wire())
                .append('.');
        sb.append("</p></body></html>");
        return sb.toString();
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String integer(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
