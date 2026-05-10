package com.heikinashi.monitoring.application.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("monitoring.alerts")
public class AlertsConfig {

    private boolean auditEnabled = false;

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }
}
