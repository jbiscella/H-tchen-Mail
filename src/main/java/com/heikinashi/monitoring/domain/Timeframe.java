package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.error.UnsupportedTimeframeException;
import java.util.LinkedHashSet;
import java.util.Set;

public enum Timeframe {
    D1("1d"),
    W1("1w");

    private final String wire;

    Timeframe(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Timeframe fromWire(String value) {
        for (Timeframe t : values()) {
            if (t.wire.equals(value)) {
                return t;
            }
        }
        Set<String> supported = new LinkedHashSet<>();
        for (Timeframe t : values()) {
            supported.add(t.wire);
        }
        throw new UnsupportedTimeframeException(value, supported);
    }

    public static Set<String> supportedWires() {
        Set<String> s = new LinkedHashSet<>();
        for (Timeframe t : values()) {
            s.add(t.wire);
        }
        return Set.copyOf(s);
    }
}
