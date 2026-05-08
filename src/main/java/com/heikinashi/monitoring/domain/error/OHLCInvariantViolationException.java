package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class OHLCInvariantViolationException extends ValidationException {
    public OHLCInvariantViolationException(String barTime, String field) {
        super(
                "OHLC_INVARIANT_VIOLATION",
                "OHLC invariant violated at " + barTime + ": " + field,
                Map.of("bar_time", barTime, "field", field));
    }
}
