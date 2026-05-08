package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class InvalidWindowSizeException extends ValidationException {
    public InvalidWindowSizeException(int value) {
        super("INVALID_WINDOW_SIZE", "rolling_window_size must be >= 1: " + value, Map.of("value", value));
    }
}
