package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public abstract class NotFoundException extends DomainException {
    protected NotFoundException(String code, String message, Map<String, Object> payload) {
        super(code, message, payload);
    }
}
