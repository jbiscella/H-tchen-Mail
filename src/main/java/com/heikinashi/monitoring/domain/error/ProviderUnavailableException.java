package com.heikinashi.monitoring.domain.error;

import java.util.HashMap;
import java.util.Map;

public final class ProviderUnavailableException extends TransientException {
    public ProviderUnavailableException(String provider, Throwable cause) {
        super("PROVIDER_UNAVAILABLE", "Provider unavailable: " + provider, payload(provider, cause), cause);
    }

    private static Map<String, Object> payload(String provider, Throwable cause) {
        Map<String, Object> p = new HashMap<>();
        p.put("provider", provider);
        if (cause != null) {
            p.put("cause", cause.getClass().getSimpleName());
        }
        return p;
    }
}
