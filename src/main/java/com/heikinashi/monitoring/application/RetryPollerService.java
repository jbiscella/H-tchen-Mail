package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.application.config.AlertsConfig;
import com.heikinashi.monitoring.application.config.RetryConfig;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.AlertAuditRepository;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.ChartRenderer;
import com.heikinashi.monitoring.domain.ChartWindowPolicy;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaLookbackWindow;
import com.heikinashi.monitoring.domain.HaRepository;
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
 * <p>For each due {@link PendingAlert}: first <b>claim</b> the item (lease) via
 * the conditional retry-count bump — only the claim winner proceeds, so two
 * pollers racing on the same due snapshot produce exactly one email; the loser
 * is a complete no-op. Then attempt chart + AI + send. On success, delete the
 * pending item; on failure under {@code maxAttempts}, record {@code last_error}
 * in place (the claim already consumed the attempt); on failure at the
 * {@code maxAttempts} threshold, send a degraded email with whatever components
 * succeeded and delete the pending item.
 */
@Singleton
public class RetryPollerService {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPollerService.class);

    private final InstrumentRepository instruments;
    private final ChartRenderer chartRenderer;
    private final HaRepository haRepository;
    private final ChartWindowPolicy chartWindow;
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
            HaRepository haRepository,
            ChartWindowPolicy chartWindow,
            AiAnalyst aiAnalyst,
            EmailSender emailSender,
            PendingAlertRepository pendingAlerts,
            AlertAuditRepository auditRepo,
            Clock clock,
            RetryConfig retryConfig,
            AlertsConfig alertsConfig) {
        this.instruments = instruments;
        this.chartRenderer = chartRenderer;
        this.haRepository = haRepository;
        this.chartWindow = chartWindow;
        this.aiAnalyst = aiAnalyst;
        this.emailSender = emailSender;
        this.pendingAlerts = pendingAlerts;
        this.auditRepo = auditRepo;
        this.clock = clock;
        this.retryDelay = retryConfig.delay();
        this.maxAttempts = retryConfig.getMaxAttempts();
        this.batchLimit = retryConfig.getBatchLimit();
        this.auditEnabled = alertsConfig.isAuditEnabled();
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
        // Claim-before-processing lease (CLAUDE.md §9 "Retry queue mechanics"):
        // the attempt is consumed up front by the conditional bump, so a
        // concurrent poller that read the same due snapshot cannot also send —
        // the loser of the claim is a complete no-op. A crash after the claim
        // leaves the item scheduled at retry_at with one attempt consumed
        // (at-least-once delivery preserved).
        Instant claimTime = clock.instant();
        PendingAlert claimed = pending.bumped(claimTime.plus(retryDelay), pending.lastError());
        if (!pendingAlerts.bumpRetry(claimed, pending.retryCount())) {
            LOG.info(
                    "retry_claim_lost_race instrument_id={} bar_time={}",
                    pending.event().instrumentId(),
                    pending.event().barTime());
            return result;
        }
        result = result.plusProcessed();
        PatternEvent event = claimed.event();

        Set<String> recipients = recipientsOf(event);
        if (recipients.isEmpty()) {
            LOG.warn("retry_skip_no_recipients instrument_id={} bar_time={}", event.instrumentId(), event.barTime());
            pendingAlerts.delete(claimed.eventUid());
            return result;
        }

        // One window, both consumers (Block 18 invariant).
        List<HABar> bars = lookbackWindow(event);

        Optional<ChartImage> chart;
        try {
            chart = Optional.of(chartRenderer.renderChart(event, bars));
        } catch (ChartRenderException | DependencyUnavailableException e) {
            logRetryFailure(event, "chart", e);
            chart = Optional.empty();
        }

        Optional<AiAnalysis> analysis;
        try {
            analysis = Optional.of(aiAnalyst.analyze(event, bars));
        } catch (LLMException | DependencyUnavailableException e) {
            logRetryFailure(event, "ai", e);
            analysis = Optional.empty();
        }

        boolean lastAttempt = claimed.retryCount() >= maxAttempts;
        boolean fullyOk = chart.isPresent() && analysis.isPresent();

        if (fullyOk) {
            return sendAndFinish(claimed, event, chart.get(), analysis.get(), recipients, false, result);
        }
        if (lastAttempt) {
            return sendAndFinish(claimed, event, chart.orElse(null), analysis.orElse(null), recipients, true, result);
        }
        return recordFailure(claimed, chart.isEmpty(), analysis.isEmpty(), result);
    }

    private PollResult sendAndFinish(
            PendingAlert claimed,
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
            // Even on the last attempt, if SES itself is down we can't send anything; try again later.
            return recordFailure(claimed, chart == null, analysis == null, result);
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
            return recordFailure(claimed, chart == null, analysis == null, result);
        }

        if (auditEnabled) {
            auditRepo.recordSentAlert(event, enrichment, delivered, messageIds, clock.instant());
        }
        pendingAlerts.delete(claimed.eventUid());
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

    /**
     * Record the real failure on the already-claimed item: the claim consumed
     * the attempt (retry_count) and set retry_at, so this only replaces
     * last_error — conditional at the claimed count, no second increment.
     */
    private PollResult recordFailure(PendingAlert claimed, boolean chartFailed, boolean aiFailed, PollResult result) {
        Instant now = clock.instant();
        String component = chartFailed && aiFailed ? "chart+ai" : (chartFailed ? "chart" : "ai");
        PendingAlert next = new PendingAlert(
                claimed.eventUid(),
                claimed.event(),
                claimed.retryCount(),
                claimed.retryAt(),
                new PendingAlert.LastError(
                        chartFailed ? "CHART_RENDER_FAILED" : "LLM_ERROR",
                        "transient failure on " + component,
                        now,
                        Optional.of(component)),
                claimed.createdAt());
        boolean accepted = pendingAlerts.bumpRetry(next, claimed.retryCount());
        if (!accepted) {
            LOG.info(
                    "retry_error_write_lost_race instrument_id={} bar_time={}",
                    claimed.event().instrumentId(),
                    claimed.event().barTime());
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

    /**
     * Diagnostic ERROR log on every retry-time chart/AI failure with full
     * exception + cause chain. The retry semantics live below in
     * {@link #bumpRetry}; this is purely observability so each retry attempt
     * shows the real upstream reason in CloudWatch.
     */
    private void logRetryFailure(PatternEvent event, String component, RuntimeException cause) {
        Throwable root = cause.getCause();
        LOG.error(
                "retry_attempt_failed instrument_id={} bar_time={} component={} "
                        + "ex_class={} ex_message={} cause_class={} cause_message={}",
                event.instrumentId(),
                event.barTime(),
                component,
                cause.getClass().getName(),
                cause.getMessage() == null ? "" : cause.getMessage(),
                root == null ? "" : root.getClass().getName(),
                root == null || root.getMessage() == null ? "" : root.getMessage(),
                cause);
    }

    /**
     * The HA window for this alert, resolved ONCE and handed to both the chart renderer and
     * the AI analyst so the note cannot describe a different series than the image (Block 18
     * invariant, CLAUDE.md). Best-effort: a store failure degrades to an empty window rather
     * than propagating, which routes through the renderer's existing chart-failure path
     * instead of aborting the dispatch outright.
     */
    private List<HABar> lookbackWindow(PatternEvent event) {
        try {
            return HaLookbackWindow.forEvent(haRepository, event, chartWindow.lookbackBars());
        } catch (RuntimeException e) {
            LOG.warn(
                    "lookback_window_unavailable instrument_id={} bar_time={} ex={}",
                    event.instrumentId(),
                    event.barTime(),
                    e.toString());
            return List.of();
        }
    }
}
