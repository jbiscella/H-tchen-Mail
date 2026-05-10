package com.heikinashi.monitoring.infrastructure.bedrock;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties("monitoring.bedrock")
public class BedrockConfig {

    @NotBlank
    private String modelId = "anthropic.claude-haiku-4-5-20251001-v1:0";

    @Min(1)
    private int maxTokens = 1500;

    @Min(1)
    private int maxToolIterations = 8;

    @Min(1)
    private int toolTimeoutSeconds = 5;

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getMaxToolIterations() {
        return maxToolIterations;
    }

    public void setMaxToolIterations(int maxToolIterations) {
        this.maxToolIterations = maxToolIterations;
    }

    public int getToolTimeoutSeconds() {
        return toolTimeoutSeconds;
    }

    public void setToolTimeoutSeconds(int toolTimeoutSeconds) {
        this.toolTimeoutSeconds = toolTimeoutSeconds;
    }
}
