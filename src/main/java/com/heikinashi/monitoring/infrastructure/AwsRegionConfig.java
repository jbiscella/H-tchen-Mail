package com.heikinashi.monitoring.infrastructure;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * Compute / data-plane region (DynamoDB, Bedrock, SSM, SNS, CloudWatch).
 * SES has its own region, see {@code monitoring.ses.region}.
 */
@ConfigurationProperties("aws")
public class AwsRegionConfig {

    @NotBlank
    private String region = "eu-central-1";

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
