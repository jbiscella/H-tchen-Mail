package com.heikinashi.monitoring.infrastructure.tavily;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * Configuration for the Tavily web-search news adapter (Block 19).
 *
 * <p>{@code include-domains} is the precision lever and is deliberately <b>global</b>, never
 * per-instrument — the same shape as the promotional-title patterns. Measured against Tavily:
 * an unconstrained query for "Amadeus IT Group" returned an acoustics company and a theatre
 * production of <em>AMADEUS</em>; the domain-constrained query returned 6 of 7 genuinely
 * relevant items, including the FY26 guidance downgrade every ticker-scoped API missed.
 */
@ConfigurationProperties("monitoring.tavily")
public class TavilyConfig {

    /** Bearer token. Empty disables the provider, so a fork with no key simply skips it. */
    private String apiKey = "";

    @Min(1)
    private int timeoutSeconds = 10;

    /**
     * Financial-news domains the search is restricted to. Without this the results are general
     * web hits that merely share a word with the company name.
     */
    private List<String> includeDomains = List.of(
            "reuters.com",
            "bloomberg.com",
            "ft.com",
            "investing.com",
            "marketscreener.com",
            "zacks.com",
            "seekingalpha.com",
            "fool.com",
            "barrons.com",
            "cnbc.com");

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<String> getIncludeDomains() {
        return includeDomains;
    }

    public void setIncludeDomains(List<String> includeDomains) {
        this.includeDomains = includeDomains;
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
