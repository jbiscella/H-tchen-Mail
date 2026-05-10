package com.heikinashi.monitoring.application.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import java.time.Duration;

@ConfigurationProperties("monitoring.retry")
public class RetryConfig {

    @Min(1)
    private int maxAttempts = 3;

    @Min(1)
    private int delaySeconds = 3600;

    @Min(1)
    private int batchLimit = 100;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public void setDelaySeconds(int delaySeconds) {
        this.delaySeconds = delaySeconds;
    }

    public int getBatchLimit() {
        return batchLimit;
    }

    public void setBatchLimit(int batchLimit) {
        this.batchLimit = batchLimit;
    }

    public Duration delay() {
        return Duration.ofSeconds(delaySeconds);
    }
}
