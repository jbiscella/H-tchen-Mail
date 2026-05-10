package com.heikinashi.monitoring.application.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import java.time.Duration;

@ConfigurationProperties("monitoring.run")
public class RunConfig {

    @Min(1)
    private int softTimeoutSeconds = 780;

    public int getSoftTimeoutSeconds() {
        return softTimeoutSeconds;
    }

    public void setSoftTimeoutSeconds(int softTimeoutSeconds) {
        this.softTimeoutSeconds = softTimeoutSeconds;
    }

    public Duration softTimeout() {
        return Duration.ofSeconds(softTimeoutSeconds);
    }
}
