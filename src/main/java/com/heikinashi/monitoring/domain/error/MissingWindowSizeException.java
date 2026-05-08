package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class MissingWindowSizeException extends ValidationException {
    public MissingWindowSizeException() {
        super("MISSING_WINDOW_SIZE", "rolling_window_size is required for ROLLING_WINDOW", Map.of());
    }
}
