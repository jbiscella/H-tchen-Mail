package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class MalformedHABarException extends InternalException {
    public MalformedHABarException(String barTime) {
        super("MALFORMED_HA_BAR", "HA bar in DB is inconsistent at " + barTime, Map.of("bar_time", barTime));
    }
}
