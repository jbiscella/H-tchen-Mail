package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class ImmutableFieldException extends ValidationException {
    public ImmutableFieldException(String field) {
        super("IMMUTABLE_FIELD", "Field is immutable: " + field, Map.of("field", field));
    }
}
