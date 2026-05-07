package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class DuplicateInstrumentException extends ConflictException {
    public DuplicateInstrumentException(String ticker, String exchange) {
        super(
                "DUPLICATE_INSTRUMENT",
                "Instrument already exists: " + ticker + " on " + exchange,
                Map.of("ticker", ticker, "exchange", exchange));
    }
}
