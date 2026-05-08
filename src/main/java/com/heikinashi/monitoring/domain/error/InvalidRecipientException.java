package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class InvalidRecipientException extends ValidationException {
    public InvalidRecipientException(String recipient) {
        super("INVALID_RECIPIENT", "Invalid recipient email: " + recipient, Map.of("recipient", recipient));
    }
}
