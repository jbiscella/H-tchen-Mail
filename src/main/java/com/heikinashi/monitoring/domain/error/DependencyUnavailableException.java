package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class DependencyUnavailableException extends TransientException {
    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(
                "DEPENDENCY_UNAVAILABLE",
                "Dependency unavailable: " + dependency,
                Map.of("dependency", dependency),
                cause);
    }
}
