package com.heikinashi.monitoring.domain.error;

import java.util.HashMap;
import java.util.Map;

public final class InvalidPatternConfigException extends ValidationException {
    public InvalidPatternConfigException(String pattern) {
        super("INVALID_PATTERN_CONFIG", "Unknown pattern: " + pattern, Map.of("pattern", pattern));
    }

    public InvalidPatternConfigException(String pattern, String field, Object value) {
        super(
                "INVALID_PATTERN_CONFIG",
                "Invalid " + pattern + "." + field + ": " + value,
                buildPayload(pattern, field, value));
    }

    private static Map<String, Object> buildPayload(String pattern, String field, Object value) {
        Map<String, Object> p = new HashMap<>();
        p.put("pattern", pattern);
        p.put("field", field);
        p.put("value", value);
        return p;
    }
}
