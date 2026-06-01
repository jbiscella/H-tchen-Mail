package com.heikinashi.monitoring.domain.error;

import java.util.Map;

/**
 * Raised when an imported strategy names a market condition that has no
 * supported equivalent (Block 15). The import fails loud — no partial monitoring
 * of the strategy is established — and the offending condition is named.
 */
public final class StrategyImportException extends ValidationException {

    public StrategyImportException(String condition, String reason) {
        super(
                "STRATEGY_IMPORT_FAILED",
                "Unsupported strategy condition '" + condition + "': " + reason,
                Map.of("condition", condition, "reason", reason));
    }
}
