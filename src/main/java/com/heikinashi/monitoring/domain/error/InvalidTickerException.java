package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class InvalidTickerException extends ValidationException {
    public InvalidTickerException(String field, String value) {
        super(
                "INVALID_TICKER",
                "Invalid ticker: field=" + field + " value=" + value,
                Map.of("field", field, "value", value));
    }
}
