package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public abstract class InternalException extends DomainException {
    protected InternalException(String code, String message, Map<String, Object> payload) {
        super(code, message, payload);
    }

    protected InternalException(String code, String message, Map<String, Object> payload, Throwable cause) {
        super(code, message, payload, cause);
    }
}
