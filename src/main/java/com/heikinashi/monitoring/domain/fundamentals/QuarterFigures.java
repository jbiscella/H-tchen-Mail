package com.heikinashi.monitoring.domain.fundamentals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record QuarterFigures(
        Instant quarterEnd,
        Optional<BigDecimal> revenue,
        Optional<BigDecimal> netIncome,
        Optional<BigDecimal> operatingCashFlow) {

    public QuarterFigures {
        Objects.requireNonNull(quarterEnd, "quarterEnd");
        Objects.requireNonNull(revenue, "revenue");
        Objects.requireNonNull(netIncome, "netIncome");
        Objects.requireNonNull(operatingCashFlow, "operatingCashFlow");
    }
}
