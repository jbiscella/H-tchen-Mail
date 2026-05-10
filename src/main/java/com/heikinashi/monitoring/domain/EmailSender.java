package com.heikinashi.monitoring.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Port for composing + sending the multipart alert email (CLAUDE.md §9).
 *
 * <p>Implementations fan out per recipient (no BCC) and isolate per-recipient
 * SES failures: a single rejected recipient logs but does not surface as an
 * exception that would trigger a retry.
 */
public interface EmailSender {

    /** Send a fully-enriched alert. Returns one delivery result per recipient. */
    List<DeliveryResult> sendFull(PatternEvent event, ChartImage chart, AiAnalysis analysis, Set<String> recipients);

    /** Send a degraded alert (chart and/or AI may be absent after retries). */
    List<DeliveryResult> sendDegraded(
            PatternEvent event,
            Optional<ChartImage> chart,
            Optional<AiAnalysis> analysis,
            Set<String> recipients,
            AlertEnrichment enrichment);

    record DeliveryResult(
            String recipient, boolean delivered, Optional<String> sesMessageId, Optional<String> errorCode) {}
}
