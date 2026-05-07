package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public abstract class TransientException extends DomainException {
    protected TransientException(String code, String message, Map<String, Object> payload) {
        super(code, message, payload);
    }

    protected TransientException(String code, String message, Map<String, Object> payload, Throwable cause) {
        super(code, message, payload, cause);
    }
}
