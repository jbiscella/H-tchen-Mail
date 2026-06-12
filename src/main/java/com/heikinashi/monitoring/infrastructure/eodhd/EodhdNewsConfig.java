package com.heikinashi.monitoring.infrastructure.eodhd;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;

/**
 * Block 17 knobs for the EODHD news adapter. Endpoint, token and timeout are
 * NOT here — they reuse {@link EodhdConfig} (same credential and base URL as
 * OHLC ingestion); this section only carries what is news-specific.
 */
@ConfigurationProperties("monitoring.eodhd.news")
public class EodhdNewsConfig {

    /**
     * EODHD {@code content} is a full article body (kilobytes); the summary is
     * truncated to this many characters at a word boundary (CLAUDE.md Block 17).
     */
    @Min(1)
    private int summaryMaxChars = 600;

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }
}
