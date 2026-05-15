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

    public static PatternKind fromWire(String value) {
        for (PatternKind k : values()) {
            // FORCED is synthetic — never appears in user config — so user input
            // pretending to enable/disable it is rejected the same as any
            // unknown name.
            if (k == FORCED) continue;
            if (k.wire.equals(value)) {
                return k;
            }
        }
        throw new InvalidPatternConfigException(value);
    }
}
