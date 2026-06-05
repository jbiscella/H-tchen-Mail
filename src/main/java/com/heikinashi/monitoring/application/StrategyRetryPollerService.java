package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.application.config.RetryConfig;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlertRepository;
import com.heikinashi.monitoring.domain.PollResult;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.LLMException;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SI-3c.3 — retry poller for strategy alerts (CLAUDE.md §9 Component 1c).
 *
 * <p>Mirrors {@link RetryPollerService} but over the {@code STRATEGY_PENDING_ALERT}
 * partition, with one twist: a strategy chart cannot be re-derived from the
 * alert, so the queued item carries the already-rendered chart bytes. Per due
 * item: <b>reuse</b> the stored chart (never re-render), re-run the AI analyst
 * from the stored alert, then send. On success delete; under the cap bump; at the
 * cap send a degraded email (stored chart if any, AI section empty if AI still
 * fails) and delete.
 */
@Singleton
public class StrategyRetryPollerService {

    private static final Logger LOG = LoggerFactory.getLogger(StrategyRetryPollerService.class);

    private final InstrumentRepository instruments;
    private final AiAnalyst aiAnalyst;
    private final EmailSender emailSender;
    private final PendingStrategyAlertRepository pendingAlerts;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxAttempts;
    private final int batchLimit;

    public StrategyRetryPollerService(
            InstrumentRepository instruments,
            AiAnalyst aiAnalyst,
            EmailSender emailSender,
            PendingStrategyAlertRepository pendingAlerts,
            Clock clock,
            RetryConfig retryConfig) {
        this.instruments = instruments;
        this.aiAnalyst = aiAnalyst;
        this.emailSender = emailSender;
        this.pendingAlerts = pendingAlerts;
        this.clock = clock;
        this.retryDelay = retryConfig.delay();
        this.maxAttempts = retryConfig.getMaxAttempts();
        this.batchLimit = retryConfig.getBatchLimit();
    }

    public PollResult processBatch() {
        Instant now = clock.instant();
        PollResult result = PollResult.empty();
        List<PendingStrategyAlert> due = pendingAlerts.queryDue(now, batchLimit);
        for (PendingStrategyAlert pending : due) {
            result = processOne(pending, result);
        }
        return result;
    }

    private PollResult processOne(PendingStrategyAlert pending, PollResult result) {
        result = result.plusProcessed();
        StrategyAlert alert = pending.alert();

        Set<String> recipients = recipientsOf(alert.instrumentId());
        if (recipients.isEmpty()) {
            LOG.warn(
                    "strategy_retry_skip_no_recipients instrument_id={} bar_time={}",
                    alert.instrumentId(),
                    alert.barTime());
            pendingAlerts.delete(pending.eventUid());
            return result;
        }

        // The chart is reused as-is; the poller never re-renders (no Strategy + bars here).
        Optional<ChartImage> chart = pending.chart();

        Optional<AiAnalysis> analysis;
        try {
            analysis = Optional.of(aiAnalyst.analyze(alert));
        } catch (LLMException | DependencyUnavailableException e) {
            logRetryFailure(alert, "ai", e);
            analysis = Optional.empty();
        }

        boolean lastAttempt = pending.retryCount() + 1 >= maxAttempts;
        boolean fullyOk = chart.isPresent() && analysis.isPresent();

        if (fullyOk) {
            return sendAndFinish(pending, alert, chart, analysis, recipients, false, result);
        }
        if (lastAttempt) {
            return sendAndFinish(pending, alert, chart, analysis, recipients, true, result);
        }
        return bumpRetry(pending, chart.isEmpty(), analysis.isEmpty(), result);
    }

    private PollResult sendAndFinish(
            PendingStrategyAlert pending,
            StrategyAlert alert,
            Optional<ChartImage> chart,
            Optional<AiAnalysis> analysis,
            Set<String> recipients,
            boolean degraded,
            PollResult result) {
        AlertEnrichment enrichment = AlertEnrichment.of(chart.isPresent(), analysis.isPresent());
        List<EmailSender.DeliveryResult> deliveries;
        try {
            if (degraded) {
                deliveries = emailSender.sendDegraded(alert, chart, analysis, recipients, enrichment);
            } else {
                deliveries = emailSender.sendFull(alert, chart.get(), analysis.get(), recipients);
            }
        } catch (DependencyUnavailableException e) {
            return bumpRetry(pending, chart.isEmpty(), analysis.isEmpty(), result);
        }

        Set<String> delivered = new LinkedHashSet<>();
        for (EmailSender.DeliveryResult r : deliveries) {
            if (r.delivered()) {
                delivered.add(r.recipient());
            }
        }
        if (delivered.isEmpty()) {
            // SES responded but rejected every recipient. SES being DOWN is the
            // transient case handled above (exception -> bump). Here the rejection is
            // a permanent, invalid recipient list: on the final attempt, drop the
            // poison item instead of bumping it forever (CLAUDE.md §9 Component 1c).
            // Keyed on lastAttempt, not `degraded` — a fully-enriched send on the
            // final attempt can still have every recipient rejected.
            boolean lastAttempt = pending.retryCount() + 1 >= maxAttempts;
            if (lastAttempt) {
                pendingAlerts.delete(pending.eventUid());
                LOG.error(
                        "strategy_retry_dropped_all_rejected instrument_id={} bar_time={} retry_count={}",
                        alert.instrumentId(),
                        alert.barTime(),
                        pending.retryCount());
                return result; // dropped (not sent, not requeued); already counted as processed
            }
            return bumpRetry(pending, chart.isEmpty(), analysis.isEmpty(), result);
        }

        pendingAlerts.delete(pending.eventUid());
        if (degraded) {
            LOG.info(
                    "strategy_retry_sent_degraded instrument_id={} bar_time={} enrichment={}",
                    alert.instrumentId(),
                    alert.barTime(),
                    enrichment.wire());
            return result.plusSentDegraded();
        }
        return result.plusSentFull();
    }

    private PollResult bumpRetry(
            PendingStrategyAlert pending, boolean chartFailed, boolean aiFailed, PollResult result) {
        Instant now = clock.instant();
        String component = chartFailed && aiFailed ? "chart+ai" : (chartFailed ? "chart" : "ai");
        PendingStrategyAlert next = pending.bumped(
                now.plus(retryDelay),
                new PendingAlert.LastError(
                        chartFailed ? "CHART_RENDER_FAILED" : "LLM_ERROR",
                        "transient failure on " + component,
                        now,
                        Optional.of(component)));
        boolean accepted = pendingAlerts.bumpRetry(next, pending.retryCount());
        if (!accepted) {
            LOG.info(
                    "strategy_retry_bump_lost_race instrument_id={} bar_time={}",
                    pending.alert().instrumentId(),
                    pending.alert().barTime());
        }
        return result.plusRequeued();
    }

    private Set<String> recipientsOf(String instrumentId) {
        return instruments
                .findConfigById(instrumentId)
                .map(InstrumentConfig::recipients)
                .orElse(Set.of());
    }

    private void logRetryFailure(StrategyAlert alert, String component, RuntimeException cause) {
        Throwable root = cause.getCause();
        LOG.error(
                "strategy_retry_attempt_failed instrument_id={} bar_time={} component={} "
                        + "ex_class={} ex_message={} cause_class={} cause_message={}",
                alert.instrumentId(),
                alert.barTime(),
                component,
                cause.getClass().getName(),
                cause.getMessage() == null ? "" : cause.getMessage(),
                root == null ? "" : root.getClass().getName(),
                root == null || root.getMessage() == null ? "" : root.getMessage(),
                cause);
    }
}
