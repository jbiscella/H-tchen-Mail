package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Persistence port for the alert audit trail (CLAUDE.md §2 ALERT, §9). */
public interface AlertAuditRepository {

    /**
     * Record that an alert email was successfully sent for {@code event}, with
     * the resolved enrichment level and any per-recipient SES message-IDs.
     */
    void recordSentAlert(
            PatternEvent event,
            AlertEnrichment enrichment,
            Set<String> deliveredRecipients,
            List<String> sesMessageIds,
            Instant sentAt);

    /**
     * Record that a strategy alert email was successfully sent (CLAUDE.md §9
     * Component 1c SI-3c.3). Reuses the legacy {@code ALERT} item shape with
     * {@code pattern = "strategy"} and {@code subtype = <strategy name>}, so
     * strategy sends appear in the same compliance history as legacy alerts.
     */
    void recordSentStrategyAlert(
            StrategyAlert alert,
            AlertEnrichment enrichment,
            Set<String> deliveredRecipients,
            List<String> sesMessageIds,
            Instant sentAt);
}
