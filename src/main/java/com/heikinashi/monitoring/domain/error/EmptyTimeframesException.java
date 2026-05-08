package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class EmptyTimeframesException extends ValidationException {
    public EmptyTimeframesException() {
        super("EMPTY_TIMEFRAMES", "tracked_timeframes must be non-empty", Map.of());
    }
}
