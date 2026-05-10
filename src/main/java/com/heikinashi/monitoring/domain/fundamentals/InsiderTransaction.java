package com.heikinashi.monitoring.domain.fundamentals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record InsiderTransaction(
        String insider, String transactionType, Optional<BigDecimal> shares, Instant transactionDate) {

    public InsiderTransaction {
        Objects.requireNonNull(insider, "insider");
        Objects.requireNonNull(transactionType, "transactionType");
        Objects.requireNonNull(shares, "shares");
        Objects.requireNonNull(transactionDate, "transactionDate");
    }
}
