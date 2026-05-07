package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class InstrumentNotFoundException extends NotFoundException {
    public InstrumentNotFoundException(String id) {
        super("INSTRUMENT_NOT_FOUND", "Instrument not found: " + id, Map.of("id", id));
    }
}
