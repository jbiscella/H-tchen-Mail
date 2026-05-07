package com.heikinashi.monitoring.infrastructure.dynamodb;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties("monitoring.dynamodb")
public class DynamoTableConfig {

    @NotBlank
    private String tableName = "monitoring";

    @NotBlank
    private String gsi1Name = "gsi_status";

    @NotBlank
    private String gsi2Name = "gsi_retry_due";

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getGsi1Name() {
        return gsi1Name;
    }

    public void setGsi1Name(String gsi1Name) {
        this.gsi1Name = gsi1Name;
    }

    public String getGsi2Name() {
        return gsi2Name;
    }

    public void setGsi2Name(String gsi2Name) {
        this.gsi2Name = gsi2Name;
    }
}
