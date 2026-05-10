package com.heikinashi.monitoring.domain;

/** Confidence label emitted by the AI analyst (CLAUDE.md §9). */
public enum AiConfidence {
    LOW,
    MEDIUM,
    HIGH;

    public String wire() {
        return name();
    }

    public static AiConfidence fromWire(String s) {
        return AiConfidence.valueOf(s.toUpperCase(java.util.Locale.ROOT));
    }
}
