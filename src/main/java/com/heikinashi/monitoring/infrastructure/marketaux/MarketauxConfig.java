package com.heikinashi.monitoring.infrastructure.marketaux;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Configuration for the Marketaux news adapter. The {@code api-key} is set via
 * the {@code MONITORING_MARKETAUX_API_KEY} env var (terraform/main/lambda.tf,
 * populated from the {@code MARKETAUX_KEY} GitHub secret) — no default, boot
 * fails fast if missing, same contract as the EODHD key.
 */
@ConfigurationProperties("monitoring.marketaux")
public class MarketauxConfig {

    @NotBlank
    private String apiKey;

    @NotBlank
    private String baseUrl = "https://api.marketaux.com/v1";

    @Min(1)
    private int timeoutSeconds = 10;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
