package com.heikinashi.monitoring.domain.error;

import java.util.Map;
import java.util.Set;

public final class UnsupportedTimeframeException extends ValidationException {
    public UnsupportedTimeframeException(String invalid, Set<String> supported) {
        super(
                "UNSUPPORTED_TIMEFRAME",
                "Unsupported timeframe: " + invalid,
                Map.of("invalid", invalid, "supported", Set.copyOf(supported)));
    }
}
