package com.heikinashi.monitoring.domain.error;

import java.util.HashMap;
import java.util.Map;

public final class ChartRenderException extends TransientException {
    public ChartRenderException(Throwable cause) {
        super(
                "CHART_RENDER_FAILED",
                "Chart rendering failed: " + (cause == null ? "" : cause.getMessage()),
                payload(cause),
                cause);
    }

    private static Map<String, Object> payload(Throwable cause) {
        Map<String, Object> p = new HashMap<>();
        if (cause != null) {
            p.put("cause", cause.getClass().getSimpleName());
        }
        return p;
    }
}
