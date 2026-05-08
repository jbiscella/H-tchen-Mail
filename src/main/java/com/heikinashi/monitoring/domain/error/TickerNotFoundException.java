package com.heikinashi.monitoring.domain.error;

import java.util.Map;

public final class TickerNotFoundException extends NotFoundException {
    public TickerNotFoundException(String ticker, String exchange) {
        super(
                "TICKER_NOT_FOUND",
                "Ticker not found at provider: " + ticker + " on " + exchange,
                Map.of("ticker", ticker, "exchange", exchange));
    }
}
