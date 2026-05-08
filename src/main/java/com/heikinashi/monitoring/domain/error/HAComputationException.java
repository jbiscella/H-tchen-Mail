package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class HAComputationException extends InternalException {
    public HAComputationException(String barTime, Throwable cause) {
        super(
                "HA_COMPUTATION_FAILED",
                "HA computation failed at " + barTime + ": " + (cause == null ? "" : cause.getMessage()),
                Map.of("bar_time", barTime),
                cause);
    }
}
