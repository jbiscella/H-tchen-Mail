package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.AlertAuditRepository;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.ChartRenderer;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingAlertRepository;
import com.heikinashi.monitoring.domain.PollResult;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.DomainException;
import com.heikinashi.monitoring.domain.error.LLMException;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block 6 — retry poller (CLAUDE.md §9).
 *
 * <p>For each due {@link PendingAlert}: attempt chart + AI + send. On success,
 * delete the pending item; on failure under {@code maxAttempts}, bump retry
 * via a conditional update; on failure at the {@code maxAttempts} threshold,
 * send a degraded email with whatever components succeeded and delete the
 * pending item.
 */
@Singleton
public class RetryPollerService {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPollerService.class);

    private final InstrumentRepository instruments;
    private final ChartRenderer chartRenderer;
    private final AiAnalyst aiAnalyst;
    private final EmailSender emailSender;
    private final PendingAlertRepository pendingAlerts;
    private final AlertAuditRepository auditRepo;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxAttempts;
    private final int batchLimit;
    private final boolean auditEnabled;

    public RetryPollerService(
            InstrumentRepository instruments,
            ChartRenderer chartRenderer,
            AiAnalyst aiAnalyst,
            EmailSender emailSender,
            PendingAlertRepository pendingAlerts,
            AlertAuditRepository auditRepo,
            Clock clock,
            Duration retryDelay,
            int maxAttempts,
            int batchLimit,
            boolean auditEnabled) {
        this.instruments = instruments;
        this.chartRenderer = chartRenderer;
        this.aiAnalyst = aiAnalyst;
        this.emailSender = emailSender;
        this.pendingAlerts = pendingAlerts;
        this.auditRepo = auditRepo;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.maxAttempts = maxAttempts;
        this.batchLimit = batchLimit;
        this.auditEnabled = auditEnabled;
    }

    public PollResult processBatch() {
        Instant now = clock.instant();
        PollResult result = PollResult.empty();
        List<PendingAlert> due = pendingAlerts.queryDue(now, batchLimit);
        for (PendingAlert pending : due) {
            result = processOne(pending, result);
        }
        return result;
    }

    private PollResult processOne(PendingAlert pending, PollResult result) {
        result = result.plusProcessed();
        PatternEvent event = pending.event();

        Set<String> recipients = recipientsOf(event);
        if (recipients.isEmpty()) {
            LOG.warn("retry_skip_no_recipients instrument_id={} bar_time={}", event.instrumentId(), event.barTime());
            pendingAlerts.delete(pending.eventUid());
            return result;
        }

        Optional<ChartImage> chart;
        try {
            chart = Optional.of(chartRenderer.renderChart(event));
        } catch (ChartRenderException | DependencyUnavailableException e) {
            chart = Optional.empty();
        }

        Optional<AiAnalysis> analysis;
        try {
            analysis = Optional.of(aiAnalyst.analyze(event));
        } catch (LLMException | DependencyUnavailableException e) {
            analysis = Optional.empty();
        }

        boolean lastAttempt = pending.retryCount() + 1 >= maxAttempts;
        boolean fullyOk = chart.isPresent() && analysis.isPresent();

        if (fullyOk) {
            return sendAndFinish(pending, event, chart.get(), analysis.get(), recipients, false, result);
        }
        if (lastAttempt) {
            return sendAndFinish(pending, event, chart.orElse(null), analysis.orElse(null), recipients, true, result);
        }
        return bumpRetry(pending, chart.isEmpty(), analysis.isEmpty(), result);
    }

    private PollResult sendAndFinish(
            PendingAlert pending,
            PatternEvent event,
            ChartImage chart,
            AiAnalysis analysis,
            Set<String> recipients,
            boolean degraded,
            PollResult result) {
        AlertEnrichment enrichment = AlertEnrichment.of(chart != null, analysis != null);
        List<EmailSender.DeliveryResult> deliveries;
        try {
            if (degraded) {
                deliveries = emailSender.sendDegraded(
                        event, Optional.ofNullable(chart), Optional.ofNullable(analysis), recipients, enrichment);
            } else {
                deliveries = emailSender.sendFull(event, chart, analysis, recipients);
            }
        } catch (DependencyUnavailableException e) {
            // Even on the last attempt, if SES itself is down we can't send anything; bump and try later.
            return bumpRetry(pending, chart == null, analysis == null, result);
        }

        Set<String> delivered = new LinkedHashSet<>();
        List<String> messageIds = new ArrayList<>();
        for (EmailSender.DeliveryResult r : deliveries) {
            if (r.delivered()) {
                delivered.add(r.recipient());
                r.sesMessageId().ifPresent(messageIds::add);
            }
        }
        if (delivered.isEmpty()) {
            return bumpRetry(pending, chart == null, analysis == null, result);
        }

        if (auditEnabled) {
            auditRepo.recordSentAlert(event, enrichment, delivered, messageIds, clock.instant());
        }
        pendingAlerts.delete(pending.eventUid());
        if (degraded) {
            LOG.info(
                    "retry_sent_degraded instrument_id={} bar_time={} enrichment={}",
                    event.instrumentId(),
                    event.barTime(),
                    enrichment.wire());
            return result.plusSentDegraded();
        }
        return result.plusSentFull();
    }

    private PollResult bumpRetry(PendingAlert pending, boolean chartFailed, boolean aiFailed, PollResult result) {
        Instant now = clock.instant();
        String component = chartFailed && aiFailed ? "chart+ai" : (chartFailed ? "chart" : "ai");
        PendingAlert next = pending.bumped(
                now.plus(retryDelay),
                new PendingAlert.LastError(
                        chartFailed ? "CHART_RENDER_FAILED" : "LLM_ERROR",
                        "transient failure on " + component,
                        now,
                        Optional.of(component)));
        boolean accepted = pendingAlerts.bumpRetry(next, pending.retryCount());
        if (!accepted) {
            LOG.info(
                    "retry_bump_lost_race instrument_id={} bar_time={}",
                    pending.event().instrumentId(),
                    pending.event().barTime());
        }
        return result.plusRequeued();
    }

    private Set<String> recipientsOf(PatternEvent event) {
        return instruments
                .findConfigById(event.instrumentId())
                .map(InstrumentConfig::recipients)
                .orElse(Set.of());
    }

    @SuppressWarnings("unused")
    private static String codeOf(RuntimeException e) {
        return e instanceof DomainException de ? de.code() : e.getClass().getSimpleName();
    }
}
