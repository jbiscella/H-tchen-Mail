package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class SchemaDriftException extends InternalException {
    public SchemaDriftException(String endpoint, String payloadSample) {
        super(
                "SCHEMA_DRIFT",
                "Provider response could not be parsed: " + endpoint,
                Map.of("endpoint", endpoint, "payload_sample", truncate(payloadSample)));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 256 ? s : s.substring(0, 256) + "...";
    }
}
