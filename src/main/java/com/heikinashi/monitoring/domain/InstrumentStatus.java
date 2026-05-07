package com.heikinashi.monitoring.domain;

public enum InstrumentStatus {
    ACTIVE("active"),
    ARCHIVED("archived");

    private final String wire;

    InstrumentStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static InstrumentStatus fromWire(String value) {
        for (InstrumentStatus s : values()) {
            if (s.wire.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown instrument status: " + value);
    }
}
