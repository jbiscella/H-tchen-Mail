package com.heikinashi.monitoring.domain.fundamentals;

import java.time.Instant;
import java.util.Objects;

public record NewsHeadline(String title, Instant publishedAt, String source, String url) {
    public NewsHeadline {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(url, "url");
    }
}
