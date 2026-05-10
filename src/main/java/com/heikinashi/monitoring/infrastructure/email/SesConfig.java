package com.heikinashi.monitoring.infrastructure.email;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * SES lives in a different region from compute (CLAUDE.md §1 ADR).
 */
@ConfigurationProperties("monitoring.ses")
public class SesConfig {

    @NotBlank
    private String region = "eu-south-1";

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
