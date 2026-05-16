package com.heikinashi.monitoring.domain.fundamentals;

import java.time.Instant;
import java.util.Objects;

/**
 * One news item attached to an instrument. {@code summary} is a short
 * provider-supplied blurb (Marketaux {@code description}/{@code snippet}, the
 * RSS {@code <description>}) — it may be empty when the feed gives none.
 */
public record NewsHeadline(String title, Instant publishedAt, String source, String url, String summary) {
    public NewsHeadline {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(summary, "summary");
    }
}
