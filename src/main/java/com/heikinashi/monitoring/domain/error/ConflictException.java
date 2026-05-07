package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public abstract class ConflictException extends DomainException {
    protected ConflictException(String code, String message, Map<String, Object> payload) {
        super(code, message, payload);
    }
}
