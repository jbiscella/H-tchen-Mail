package com.heikinashi.monitoring.infrastructure.email;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiConfidence;
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

    // --- "Quiet terminal" HTML theme (palette + metrics from the design) -
    private static final String INK = "#1A1814"; // rgb(26,24,20)   primary text
    private static final String MUTED = "#6E6961"; // rgb(110,105,97) labels, lede
    private static final String BODY = "#3A3530"; // rgb(58,53,48)   analysis prose
    private static final String FAINT = "#8A857C"; // rgb(138,133,124) footer
    private static final String ACCENT = "#A04030"; // rgb(160,64,48)  terracotta
    private static final String CREAM = "#F4F1EA"; // page background
    private static final String RULE = "#D9D2C3"; // rgb(217,210,195) hairlines
    private static final String MONO = "font-family:'Courier New',Courier,monospace;";
    private static final String SANS = "font-family:Helvetica,Arial,sans-serif;";

    // Legal disclaimer footer — rendered as a "quiet terminal" footer row
    // (monospace, FAINT, lowercase, middle-dot separators) consistent with
    // the id/sources/enrichment rows above it. The text covers the four
    // substantive points (automated, not advice, past-performance caveat,
    // AS IS / open-source) without shouty bold or caps.
    private static final String DISCLAIMER_FOOTER = "<div style=\"" + MONO + "font-size:10px;line-height:1.8;color:"
            + FAINT
            + ";\">disclaimer &middot; automated alert from historical pattern detection"
            + " &middot; not financial advice &middot; past performance not indicative"
            + " &middot; provided AS IS under open-source license &middot; use at own risk</div>";

    static String html(
            PatternEvent event, Optional<String> chartCid, Optional<AiAnalysis> analysis, AlertEnrichment enrichment) {
        String tickerInk =
                "<span style=\"color:" + INK + ";\">" + esc(event.ticker()) + "." + esc(event.exchange()) + "</span>";
        String ticker = esc(event.ticker()) + "." + esc(event.exchange());
        String pat = esc(event.pattern().wire()) + "/" + esc(event.subtype().wire());
        String tf = esc(event.timeframe().wire());
        String barDate = event.barTime().toString().substring(0, 10);
        String detDate = event.detectedAt().toString().substring(0, 10);
        String detTime = event.detectedAt().toString().substring(11, 16);

        StringBuilder sb = new StringBuilder(4096);
        // Force a light render: the meta tags + color-scheme tell clients
        // that honour them (Apple Mail, most webmail) not to dark-mode-invert.
        // Gmail's Android dark mode may still invert — there is no reliable
        // defence there — but the design stays light everywhere else.
        sb.append("<html><head>")
                .append("<meta name=\"color-scheme\" content=\"light\">")
                .append("<meta name=\"supported-color-schemes\" content=\"light\">")
                .append("<style>:root{color-scheme:light only;supported-color-schemes:light;}</style>")
                .append("</head><body style=\"margin:0;padding:24px 0;background:")
                .append(CREAM)
                .append(";\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:")
                .append(CREAM)
                .append(";\"><tr><td align=\"center\">");
        sb.append("<table role=\"presentation\" width=\"640\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:640px;background:#FFFFFF;\"><tr><td style=\"padding:40px 44px 36px;\">");

        // Header: brand + timestamp (11px mono, muted).
        sb.append("<table role=\"presentation\" width=\"100%\"><tr><td style=\"")
                .append(MONO)
                .append("font-size:11px;color:")
                .append(MUTED)
                .append(";\">biscella.signals</td><td align=\"right\" style=\"")
                .append(MONO)
                .append("font-size:11px;color:")
                .append(MUTED)
                .append(";\">")
                .append(detDate)
                .append(" &middot; ")
                .append(detTime)
                .append(" UTC</td></tr></table>");
        sb.append(rule());

        // Summary key/value table — readable for non-technical readers.
        sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\">")
                .append(kvRow("INSTRUMENT", ticker))
                .append(kvRow(
                        "PATTERN",
                        esc(event.pattern().wire()) + " &middot; "
                                + esc(event.subtype().wire())))
                .append(kvRow("TIMEFRAME", timeframeWord(event.timeframe())))
                .append("</table>");
        sb.append(rule());

        // Heading + lede (Helvetica).
        sb.append("<div style=\"")
                .append(SANS)
                .append("font-size:26px;font-weight:700;letter-spacing:-0.4px;line-height:30px;color:")
                .append(INK)
                .append(";padding:14px 0 6px;\">Heikin-Ashi pattern detected.</div>");
        sb.append("<div style=\"")
                .append(SANS)
                .append("font-size:14px;line-height:21px;color:")
                .append(MUTED)
                .append(";padding-bottom:20px;\">")
                .append(tickerInk)
                .append(" printed a ")
                .append(pat)
                .append(" bar on the ")
                .append(tf)
                .append(" chart, closing at ")
                .append(money(event.barSnapshot().close()))
                .append(" on ")
                .append(barDate)
                .append(".</div>");

        // Chart (no CSS border — the PNG carries its own framing).
        if (chartCid.isPresent()) {
            sb.append("<img src=\"cid:")
                    .append(esc(chartCid.get()))
                    .append("\" alt=\"Heikin Ashi chart\" width=\"552\" " + "style=\"display:block;width:100%;\">");
        } else {
            sb.append("<div style=\"border:1px solid ")
                    .append(RULE)
                    .append(";padding:36px 12px;text-align:center;")
                    .append(MONO)
                    .append("font-size:13px;color:")
                    .append(MUTED)
                    .append(";\">chart unavailable &middot; pattern ")
                    .append(pat)
                    .append("</div>");
        }
        sb.append("<table role=\"presentation\" width=\"100%\"><tr><td style=\"padding:8px 0;")
                .append(MONO)
                .append("font-size:11px;color:")
                .append(MUTED)
                .append(";\">")
                .append(timeframeWord(event.timeframe()))
                .append("</td><td align=\"right\" style=\"padding:8px 0;")
                .append(MONO)
                .append("font-size:11px;color:")
                .append(MUTED)
                .append(";\">price (HA)</td></tr></table>");

        // Pattern values table.
        sb.append(sectionLabel("// pattern values"));
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                .append(headCell(""))
                .append(headCell("OPEN"))
                .append(headCell("HIGH"))
                .append(headCell("LOW"))
                .append(headCell("CLOSE"))
                .append("</tr>");
        sb.append(valueRow(
                "HA",
                event.barSnapshot().haOpen(),
                event.barSnapshot().haHigh(),
                event.barSnapshot().haLow(),
                event.barSnapshot().haClose()));
        sb.append(valueRow(
                "OHLC",
                event.barSnapshot().open(),
                event.barSnapshot().high(),
                event.barSnapshot().low(),
                event.barSnapshot().close()));
        sb.append("</table>");

        // Fundamental confidence.
        sb.append(rule());
        sb.append("<table role=\"presentation\" width=\"100%\"><tr><td style=\"")
                .append(MONO)
                .append("font-size:11px;letter-spacing:2px;color:")
                .append(MUTED)
                .append(";\">FUNDAMENTAL CONFIDENCE</td><td align=\"right\" style=\"")
                .append(MONO)
                .append("font-size:12px;letter-spacing:1px;color:")
                .append(INK)
                .append(";\">");
        if (analysis.isPresent()) {
            AiConfidence c = analysis.get().confidence();
            sb.append(esc(c.wire())).append("&nbsp;&nbsp;").append(dots(c));
        } else {
            sb.append("<span style=\"color:").append(MUTED).append(";\">unavailable</span>");
        }
        sb.append("</td></tr></table>");
        sb.append(rule());

        // Corroborating / contradicting.
        if (analysis.isPresent()) {
            AiAnalysis a = analysis.get();
            sb.append(sectionLabel("// corroborating"));
            sb.append(para(a.corroborating().orElse("None available.")));
            sb.append(sectionLabel("// contradicting"));
            sb.append(para(a.contradicting().orElse("None available.")));
        } else {
            sb.append(sectionLabel("// fundamental analysis"));
            sb.append(para("AI fundamental analysis unavailable for this alert."));
        }

        // Footer.
        sb.append(rule());
        sb.append("<div style=\"")
                .append(MONO)
                .append("font-size:10px;line-height:1.8;color:")
                .append(FAINT)
                .append(";\">id &middot; ")
                .append(esc(event.instrumentId()))
                .append("<br>sources &middot; ");
        if (analysis.isPresent() && !analysis.get().dataSources().isEmpty()) {
            sb.append(esc(String.join(", ", analysis.get().dataSources())));
        } else {
            sb.append("none");
        }
        sb.append(" &middot; enrichment ").append(esc(enrichment.wire())).append("</div>");

        sb.append(rule());
        sb.append(DISCLAIMER_FOOTER);
        sb.append("</td></tr></table></td></tr></table></body></html>");
        return sb.toString();
    }

    private static String rule() {
        return "<div style=\"border-top:1px solid " + RULE + ";font-size:0;line-height:0;margin:14px 0;\">&nbsp;</div>";
    }

    /** One row of the summary key/value table. {@code value} must already be HTML-safe. */
    private static String kvRow(String label, String value) {
        return "<tr><td style=\"" + MONO + "font-size:10px;letter-spacing:1.5px;color:" + MUTED
                + ";padding:3px 28px 3px 0;\">" + esc(label) + "</td><td style=\"" + MONO
                + "font-size:13px;color:" + INK + ";padding:3px 0;\">" + value + "</td></tr>";
    }

    private static String sectionLabel(String text) {
        return "<div style=\"" + MONO + "font-size:11px;letter-spacing:2px;color:" + ACCENT + ";padding:20px 0 8px;\">"
                + esc(text) + "</div>";
    }

    private static String headCell(String text) {
        String align = text.isEmpty() ? "left" : "right";
        return "<td align=\"" + align + "\" style=\"" + MONO + "font-size:10px;letter-spacing:1.5px;color:" + MUTED
                + ";padding:0 0 8px;border-bottom:1px solid " + RULE + ";\">" + esc(text) + "</td>";
    }

    private static String valueRow(String label, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c) {
        return "<tr><td style=\"" + MONO + "font-size:13px;color:" + MUTED + ";padding:8px 0;border-bottom:1px solid "
                + RULE + ";\">" + esc(label) + "</td>"
                + numCell(o, INK) + numCell(h, INK) + numCell(l, INK) + numCell(c, ACCENT)
                + "</tr>";
    }

    private static String numCell(BigDecimal v, String color) {
        return "<td align=\"right\" style=\"" + MONO + "font-size:13px;color:" + color
                + ";padding:8px 0 8px 16px;border-bottom:1px solid " + RULE + ";\">" + money(v) + "</td>";
    }

    private static String para(String text) {
        return "<div style=\"" + SANS + "font-size:13px;line-height:1.6;color:" + BODY + ";padding-bottom:4px;\">"
                + esc(text) + "</div>";
    }

    /** Four dots, filled up to the confidence level (LOW=1, MEDIUM=2, HIGH=3). */
    private static String dots(AiConfidence c) {
        int filled =
                switch (c) {
                    case LOW -> 1;
                    case MEDIUM -> 2;
                    case HIGH -> 3;
                };
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            sb.append("<span style=\"color:")
                    .append(i <= filled ? ACCENT : RULE)
                    .append(";\">&#9679;</span>");
        }
        return sb.toString();
    }

    private static String timeframeWord(com.heikinashi.monitoring.domain.Timeframe tf) {
        return switch (tf) {
            case D1 -> "daily";
            case W1 -> "weekly";
        };
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String integer(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
