package com.heikinashi.monitoring.domain.fundamentals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public record EarningsCalendar(
        Optional<Instant> nextEarningsDate,
        Optional<Instant> lastEarningsDate,
        Optional<BigDecimal> lastSurprisePercent) {

    public static EarningsCalendar empty() {
        return new EarningsCalendar(Optional.empty(), Optional.empty(), Optional.empty());
    }
}
