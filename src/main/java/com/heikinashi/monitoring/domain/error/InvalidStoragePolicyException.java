package com.heikinashi.monitoring.domain.error;

import java.util.Map;
import java.util.Set;

public final class InvalidStoragePolicyException extends ValidationException {
    public InvalidStoragePolicyException(String value, Set<String> supported) {
        super(
                "INVALID_STORAGE_POLICY",
                "Invalid storage policy: " + value,
                Map.of("value", value, "supported", Set.copyOf(supported)));
    }
}
