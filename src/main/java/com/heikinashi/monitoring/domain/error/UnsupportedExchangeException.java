package com.heikinashi.monitoring.domain.error;

import java.util.Map;
import java.util.Set;

public final class UnsupportedExchangeException extends ValidationException {
    public UnsupportedExchangeException(String value, Set<String> supported) {
        super(
                "UNSUPPORTED_EXCHANGE",
                "Unsupported exchange: " + value,
                Map.of("value", value, "supported", Set.copyOf(supported)));
    }
}
