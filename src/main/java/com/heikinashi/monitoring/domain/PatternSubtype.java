package com.heikinashi.monitoring.domain;

/** Sub-event labels emitted with each {@link PatternEvent} (CLAUDE.md §8). */
public enum PatternSubtype {
    BULLISH_REVERSAL("bullish_reversal"),
    BEARISH_REVERSAL("bearish_reversal"),
    BULLISH_STRONG("bullish_strong"),
    BEARISH_STRONG("bearish_strong"),
    DOJI("doji"),
    /** Subtype attached to {@link PatternKind#FORCED} synthetic events. */
    FORCED("forced");

    private final String wire;

    PatternSubtype(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
