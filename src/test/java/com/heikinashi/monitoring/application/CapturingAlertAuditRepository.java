package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.AlertAuditRepository;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Test fake. Captures audit-trail writes for assertions. */
public final class CapturingAlertAuditRepository implements AlertAuditRepository {

    public record Audit(
            String instrumentId,
            Instant barTime,
            AlertEnrichment enrichment,
            Set<String> recipients,
            List<String> messageIds,
            Instant sentAt) {}

    private final List<Audit> audits = new ArrayList<>();
    private boolean failNextWrite;

    public List<Audit> audits() {
        return List.copyOf(audits);
    }

    /** Prime the next audit write to throw, as if a DynamoDB PutItem were transiently down. */
    public void failNextWrite() {
        this.failNextWrite = true;
    }

    @Override
    public void recordSentAlert(
            PatternEvent event,
            AlertEnrichment enrichment,
            Set<String> deliveredRecipients,
            List<String> sesMessageIds,
            Instant sentAt) {
        audits.add(new Audit(
                event.instrumentId(),
                event.barTime(),
                enrichment,
                Set.copyOf(deliveredRecipients),
                List.copyOf(sesMessageIds),
                sentAt));
    }

    @Override
    public void recordSentStrategyAlert(
            StrategyAlert alert,
            AlertEnrichment enrichment,
            Set<String> deliveredRecipients,
            List<String> sesMessageIds,
            Instant sentAt) {
        if (failNextWrite) {
            failNextWrite = false;
            throw new RuntimeException("scripted audit PutItem failure");
        }
        audits.add(new Audit(
                alert.instrumentId(),
                alert.barTime(),
                enrichment,
                Set.copyOf(deliveredRecipients),
                List.copyOf(sesMessageIds),
                sentAt));
    }
}
