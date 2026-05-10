package com.heikinashi.monitoring.domain.fundamentals;

import java.time.Instant;
import java.util.Objects;

public record AnalystRating(String firm, String rating, Instant date) {
    public AnalystRating {
        Objects.requireNonNull(firm, "firm");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(date, "date");
    }
}
