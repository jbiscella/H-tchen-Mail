package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.error.InvalidPatternConfigException;

public enum PatternKind {
    COLOR_CHANGE("color_change"),
    STRONG_CANDLE("strong_candle"),
    DOJI("doji"),
    /**
     * Synthetic kind used only when {@code monitoring-main} runs with
     * {@code force_email=true} and a (instrument, timeframe) would otherwise
     * have no detected pattern. Never produced by the pattern detector and
     * never settable via instrument config; the orchestration layer
     * manufactures a single forced event per (instrument, timeframe) from
     * the latest persisted HA / OHLC bar so the chart + AI + email
     * pipeline runs end-to-end.
     */
    FORCED("forced");

    private final String wire;

    PatternKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * Resolve a wire string to its {@link PatternKind}. Round-trips every
     * kind, {@link #FORCED} included — this is the parser for persisted /
     * serialized data (e.g. a {@code PENDING_ALERT}'s event JSON), which must
     * read back anything {@link #wire()} ever wrote.
     *
     * <p>Rejecting {@code forced} as a <em>user-config</em> pattern name is a
     * separate concern, enforced by {@code InstrumentConfigService} — not here.
     */
    public static PatternKind fromWire(String value) {
        for (PatternKind k : values()) {
            if (k.wire.equals(value)) {
                return k;
            }
        }
        throw new InvalidPatternConfigException(value);
    }
}
