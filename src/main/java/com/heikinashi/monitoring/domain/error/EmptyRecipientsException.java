package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class EmptyRecipientsException extends ValidationException {
    public EmptyRecipientsException() {
        super("EMPTY_RECIPIENTS", "recipients must be non-empty when explicitly set", Map.of());
    }
}
