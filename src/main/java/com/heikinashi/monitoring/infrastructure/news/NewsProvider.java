package com.heikinashi.monitoring.infrastructure.news;

import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import java.util.List;

/**
 * A single news source. Implementations are thin HTTP/feed adapters; the
 * {@link NewsAggregator} fans out across all enabled ones, isolating
 * per-provider failures.
 */
public interface NewsProvider {

    /** Stable identifier used in {@code monitoring.news.providers} and logs. */
    String name();

    /**
     * Fetch up to {@code max} recent headlines for the instrument. May raise
     * {@link com.heikinashi.monitoring.domain.error.ProviderUnavailableException}
     * or {@link com.heikinashi.monitoring.domain.error.SchemaDriftException};
     * the aggregator treats any failure as "this provider contributed nothing".
     */
    List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max);
}
