package com.heikinashi.monitoring.infrastructure.news;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.util.List;

/**
 * Which news adapters {@link NewsAggregator} queries. A provider whose
 * {@link NewsProvider#name()} is not in this list is skipped — letting an
 * operator disable a source without removing its code.
 */
@ConfigurationProperties("monitoring.news")
public class NewsConfig {

    private List<String> providers = List.of("marketaux", "yahoo-rss");

    public List<String> getProviders() {
        return providers;
    }

    public void setProviders(List<String> providers) {
        this.providers = providers;
    }
}
