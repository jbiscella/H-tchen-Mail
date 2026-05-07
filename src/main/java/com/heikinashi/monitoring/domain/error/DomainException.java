package com.heikinashi.monitoring.domain.error;

import java.util.Map;
import java.util.Objects;

/**
 * Root of the domain exception hierarchy. All errors raised from the domain
 * carry a stable {@link #code()} (the public contract) and a typed payload
 * map. Messages are human-readable and may evolve; clients depend on the code.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final Map<String, Object> payload;

    protected DomainException(String code, String message, Map<String, Object> payload) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.payload = Map.copyOf(payload);
    }

    protected DomainException(String code, String message, Map<String, Object> payload, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.payload = Map.copyOf(payload);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> payload() {
        return payload;
    }
}
