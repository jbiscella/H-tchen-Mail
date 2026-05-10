package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.AlertAuditRepository;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.ChartRenderer;
import com.heikinashi.monitoring.domain.DispatchSummary;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingAlertRepository;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.DomainException;
import com.heikinashi.monitoring.domain.error.LLMException;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block 6 — Alert dispatch (CLAUDE.md §9).
 *
 * <p>For each {@link PatternEvent}: render chart → run AI → send full email
 * (per recipient). On chart / AI / sender transient failure: enqueue a
 * {@link PendingAlert} for the {@link RetryPollerService} to pick up.
 *
 * <p>This service handles only first-attempt dispatch; retries (and the
 * eventual fallback to a degraded email after 3 attempts) live in
 * {@link RetryPollerService}.
 */
@Singleton
public class AlertDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(AlertDispatchService.class);

    private final InstrumentRepository instruments;
    private final ChartRenderer chartRenderer;
    private final AiAnalyst aiAnalyst;
    private final EmailSender emailSender;
    private final PendingAlertRepository pendingAlerts;
    private final AlertAuditRepository auditRepo;
    private final Clock clock;
    private final Duration retryDelay;
    private final boolean auditEnabled;

    public AlertDispatchService(
            InstrumentRepository instruments,
            ChartRenderer chartRenderer,
            AiAnalyst aiAnalyst,
            EmailSender emailSender,
            PendingAlertRepository pendingAlerts,
            AlertAuditRepository auditRepo,
            Clock clock,
            Duration retryDelay,
            boolean auditEnabled) {
        this.instruments = instruments;
        this.chartRenderer = chartRenderer;
        this.aiAnalyst = aiAnalyst;
        this.emailSender = emailSender;
        this.pendingAlerts = pendingAlerts;
        this.auditRepo = auditRepo;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.auditEnabled = auditEnabled;
    }

    public DispatchSummary dispatchAlerts(List<PatternEvent> events) {
        DispatchSummary summary = DispatchSummary.empty();
        for (PatternEvent event : events) {
            summary = dispatchOne(event, summary);
        }
        return summary;
    }

    private DispatchSummary dispatchOne(PatternEvent event, DispatchSummary summary) {
        InstrumentConfig cfg = instruments.findConfigById(event.instrumentId()).orElse(null);
        if (cfg == null) {
            LOG.warn("dispatch_skip_no_config instrument_id={}", event.instrumentId());
            return summary.plusFailed();
        }
        Set<String> recipients = cfg.recipients();
        if (recipients.isEmpty()) {
            LOG.warn(
                    "skipping alert: no recipients for instrument {} bar_time={}",
                    event.instrumentId(),
                    event.barTime());
            return summary.plusSkipped();
        }

        ChartImage chart;
        try {
            chart = chartRenderer.renderChart(event);
        } catch (ChartRenderException | DependencyUnavailableException e) {
            return enqueueAndCount(event, "chart", e, summary);
        }

        AiAnalysis analysis;
        try {
            analysis = aiAnalyst.analyze(event);
        } catch (LLMException | DependencyUnavailableException e) {
            return enqueueAndCount(event, "ai", e, summary);
        }

        List<EmailSender.DeliveryResult> deliveries;
        try {
            deliveries = emailSender.sendFull(event, chart, analysis, recipients);
        } catch (DependencyUnavailableException e) {
            return enqueueAndCount(event, "email", e, summary);
        }

        Set<String> delivered = new java.util.LinkedHashSet<>();
        List<String> messageIds = new ArrayList<>();
        for (EmailSender.DeliveryResult r : deliveries) {
            if (r.delivered()) {
                delivered.add(r.recipient());
                r.sesMessageId().ifPresent(messageIds::add);
            } else {
                LOG.warn(
                        "ses_recipient_rejected instrument_id={} recipient_masked={} code={}",
                        event.instrumentId(),
                        mask(r.recipient()),
                        r.errorCode().orElse(""));
            }
        }
        if (delivered.isEmpty()) {
            LOG.error("ses_all_rejected instrument_id={} recipients={}", event.instrumentId(), recipients.size());
            return enqueueAndCount(
                    event, "email", new DependencyUnavailableException("ses-all-rejected", null), summary);
        }

        if (auditEnabled) {
            auditRepo.recordSentAlert(event, AlertEnrichment.FULL, delivered, messageIds, clock.instant());
        }
        return summary.plusSent();
    }

    private DispatchSummary enqueueAndCount(
            PatternEvent event, String component, RuntimeException cause, DispatchSummary summary) {
        Instant now = clock.instant();
        PendingAlert pending = new PendingAlert(
                PendingAlert.uidOf(event),
                event,
                0,
                now.plus(retryDelay),
                new PendingAlert.LastError(
                        cause instanceof DomainException de
                                ? de.code()
                                : cause.getClass().getSimpleName(),
                        cause.getMessage() == null ? "" : cause.getMessage(),
                        now,
                        java.util.Optional.of(component)),
                now);
        pendingAlerts.enqueue(pending);
        LOG.info(
                "alert_enqueued_for_retry instrument_id={} bar_time={} component={} code={}",
                event.instrumentId(),
                event.barTime(),
                component,
                pending.lastError().code());
        return summary.plusQueued();
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
