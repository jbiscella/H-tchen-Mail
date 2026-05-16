package com.heikinashi.monitoring.infrastructure.marketaux;

import com.heikinashi.monitoring.domain.Timeframe;
import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Configuration for the Marketaux news adapter. The {@code api-key} is set via
 * the {@code MONITORING_MARKETAUX_API_KEY} env var (terraform/main/lambda.tf,
 * populated from the {@code MARKETAUX_KEY} GitHub secret) — no default, boot
 * fails fast if missing, same contract as the EODHD key.
 *
 * <p>{@code recency-days-1d} / {@code recency-days-1w} bound how far back the
 * adapter's {@code published_after} filter reaches, per pattern timeframe — a
 * daily signal wants very recent news, a weekly one tolerates an older window.
 * Both are overridable via the {@code MONITORING_MARKETAUX_RECENCY_DAYS_1D/1W}
 * env vars (wired from the {@code MARKETAUX_RECENCY_DAYS_1D/1W} GitHub vars).
 */
@ConfigurationProperties("monitoring.marketaux")
public class MarketauxConfig {

    @NotBlank
    private String apiKey;

    @NotBlank
    private String baseUrl = "https://api.marketaux.com/v1";

    @Min(1)
    private int timeoutSeconds = 10;

    @Min(1)
    private int recencyDays1d = 7;

    @Min(1)
    private int recencyDays1w = 30;

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

    public int getRecencyDays1d() {
        return recencyDays1d;
    }

    public void setRecencyDays1d(int recencyDays1d) {
        this.recencyDays1d = recencyDays1d;
    }

    public int getRecencyDays1w() {
        return recencyDays1w;
    }

    public void setRecencyDays1w(int recencyDays1w) {
        this.recencyDays1w = recencyDays1w;
    }

    /** The {@code published_after} look-back window, in days, for the given pattern timeframe. */
    public int recencyDays(Timeframe tf) {
        return switch (tf) {
            case D1 -> recencyDays1d;
            case W1 -> recencyDays1w;
        };
    }
}
