package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Instrument(
        String id,
        String ticker,
        String exchange,
        Optional<String> name,
        Optional<String> currency,
        InstrumentStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Instrument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public Instrument withStatus(InstrumentStatus newStatus, Instant updatedAt) {
        return new Instrument(id, ticker, exchange, name, currency, newStatus, createdAt, updatedAt);
    }

    public Instrument withMetadata(Optional<String> newName, Optional<String> newCurrency, Instant updatedAt) {
        return new Instrument(id, ticker, exchange, newName, newCurrency, status, createdAt, updatedAt);
    }
}
