package com.heikinashi.monitoring.domain.fundamentals;

import java.math.BigDecimal;
import java.util.Optional;

/** Snapshot of fundamental metrics for an instrument (CLAUDE.md §9 tool catalog). */
public record QuoteInfo(
        Optional<String> sector,
        Optional<String> industry,
        Optional<BigDecimal> marketCap,
        Optional<BigDecimal> peRatio,
        Optional<BigDecimal> eps,
        Optional<BigDecimal> beta,
        Optional<BigDecimal> dividendYield) {

    public static QuoteInfo empty() {
        return new QuoteInfo(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
