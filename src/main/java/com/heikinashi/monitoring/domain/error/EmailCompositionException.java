package com.heikinashi.monitoring.domain.error;

import java.util.HashMap;
import java.util.Map;

public final class EmailCompositionException extends InternalException {
    public EmailCompositionException(Throwable cause) {
        super(
                "EMAIL_COMPOSITION_FAILED",
                "Email composition failed: " + (cause == null ? "" : cause.getMessage()),
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
