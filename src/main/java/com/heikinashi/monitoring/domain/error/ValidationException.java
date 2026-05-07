package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public abstract class ValidationException extends DomainException {
    protected ValidationException(String code, String message, Map<String, Object> payload) {
        super(code, message, payload);
    }
}
