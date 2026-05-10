package com.heikinashi.monitoring.domain.error;

import java.util.HashMap;
import java.util.Map;

public final class LLMException extends TransientException {
    public LLMException(String reason) {
        super("LLM_ERROR", "LLM failure: " + reason, Map.of("reason", reason));
    }

    public LLMException(String reason, Throwable cause) {
        super("LLM_ERROR", "LLM failure: " + reason, payload(reason, cause), cause);
    }

    private static Map<String, Object> payload(String reason, Throwable cause) {
        Map<String, Object> p = new HashMap<>();
        p.put("reason", reason);
        if (cause != null) {
            p.put("cause", cause.getClass().getSimpleName());
        }
        return p;
    }
}
