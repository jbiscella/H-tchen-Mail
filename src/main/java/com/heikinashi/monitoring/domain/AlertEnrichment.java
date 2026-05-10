package com.heikinashi.monitoring.domain;

/** What components made it into a sent alert (CLAUDE.md §2 ALERT.enrichment). */
public enum AlertEnrichment {
    FULL("full"),
    DEGRADED_CHART("degraded_chart"),
    DEGRADED_AI("degraded_ai"),
    DEGRADED_BOTH("degraded_both");

    private final String wire;

    AlertEnrichment(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static AlertEnrichment of(boolean chartOk, boolean aiOk) {
        if (chartOk && aiOk) return FULL;
        if (!chartOk && !aiOk) return DEGRADED_BOTH;
        return chartOk ? DEGRADED_AI : DEGRADED_CHART;
    }
}
