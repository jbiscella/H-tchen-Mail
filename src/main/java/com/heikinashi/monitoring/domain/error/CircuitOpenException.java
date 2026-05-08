package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class CircuitOpenException extends TransientException {
    public CircuitOpenException(String ticker) {
        super("CIRCUIT_OPEN", "Circuit breaker open for ticker: " + ticker, Map.of("ticker", ticker));
    }
}
