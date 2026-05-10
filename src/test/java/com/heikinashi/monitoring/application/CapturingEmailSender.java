package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.PatternEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Test fake. Records every send attempt; rejected recipients can be primed. */
public final class CapturingEmailSender implements EmailSender {

    public record Sent(
            String eventUid,
            AlertEnrichment enrichment,
            boolean degraded,
            Set<String> recipients,
            List<DeliveryResult> deliveries) {}

    private final List<Sent> sends = new ArrayList<>();
    private final Set<String> rejectedRecipients = new HashSet<>();
    private int messageIdCounter;

    public void rejectRecipient(String recipient) {
        rejectedRecipients.add(recipient);
    }

    public List<Sent> sends() {
        return List.copyOf(sends);
    }

    @Override
    public List<DeliveryResult> sendFull(
            PatternEvent event, ChartImage chart, AiAnalysis analysis, Set<String> recipients) {
        return record(event, AlertEnrichment.FULL, false, recipients);
    }

    @Override
    public List<DeliveryResult> sendDegraded(
            PatternEvent event,
            Optional<ChartImage> chart,
            Optional<AiAnalysis> analysis,
            Set<String> recipients,
            AlertEnrichment enrichment) {
        return record(event, enrichment, true, recipients);
    }

    private List<DeliveryResult> record(
            PatternEvent event, AlertEnrichment enrichment, boolean degraded, Set<String> recipients) {
        List<DeliveryResult> deliveries = new ArrayList<>(recipients.size());
        for (String r : recipients) {
            if (rejectedRecipients.contains(r)) {
                deliveries.add(new DeliveryResult(r, false, Optional.empty(), Optional.of("INVALID_RECIPIENT")));
            } else {
                deliveries.add(
                        new DeliveryResult(r, true, Optional.of("ses-msg-" + (++messageIdCounter)), Optional.empty()));
            }
        }
        sends.add(new Sent(
                com.heikinashi.monitoring.domain.PendingAlert.uidOf(event),
                enrichment,
                degraded,
                Set.copyOf(recipients),
                List.copyOf(deliveries)));
        return deliveries;
    }
}
