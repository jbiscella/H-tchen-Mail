package com.heikinashi.monitoring.domain;

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
}
