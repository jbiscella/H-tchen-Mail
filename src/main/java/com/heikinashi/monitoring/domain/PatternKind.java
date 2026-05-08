package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.error.InvalidPatternConfigException;

public enum PatternKind {
    COLOR_CHANGE("color_change"),
    STRONG_CANDLE("strong_candle"),
    DOJI("doji");

    private final String wire;

    PatternKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static PatternKind fromWire(String value) {
        for (PatternKind k : values()) {
            if (k.wire.equals(value)) {
                return k;
            }
        }
        throw new InvalidPatternConfigException(value);
    }
}
