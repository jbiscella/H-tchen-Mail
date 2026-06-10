package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.application.config.AlertsConfig;
import com.heikinashi.monitoring.application.config.RetryConfig;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.AlertAuditRepository;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlertRepository;
import com.heikinashi.monitoring.domain.PollResult;
import com.heikinashi.monitoring.domain.StrategyChartRenderer;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.LLMException;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * partition. The queued item carries no chart: per due item the poller reloads the
 * persisted {@link Strategy} + HA bars and <b>re-renders</b> the chart (falling
 * back to chart-degraded if the strategy is gone or render still fails), re-runs
 * the AI analyst from the stored alert, then sends. On success it records the audit
 * item and deletes; under the cap it bumps; at the cap it sends a degraded email
 * and deletes. All recipients rejected on the final attempt drops the poison item.
 */
@Singleton
public class StrategyRetryPollerService {

    private static final Logger LOG = LoggerFactory.getLogger(StrategyRetryPollerService.class);

    // Chart window matches the strategy evaluation lookback so any indicator a
    // scenario references (and that was warm enough to fire the alert) has enough
    // bars to render (CLAUDE.md §9 Component 1c "Chart window").
    private static final int CHART_LOOKBACK_BARS = 300;

    private final InstrumentRepository instruments;
    private final com.heikinashi.monitoring.domain.strategy.StrategyRepository strategies;
    private final StrategyChartRenderer chartRenderer;
    private final HaRepository haRepository;
    private final AiAnalyst aiAnalyst;
    private final EmailSender emailSender;
    private final PendingStrategyAlertRepository pendingAlerts;
    private final AlertAuditRepository auditRepo;
    private final Clock clock;
    private final Duration retryDelay;
    private final int maxAttempts;
    private final int batchLimit;
    private final boolean auditEnabled;

    public StrategyRetryPollerService(
            InstrumentRepository instruments,
            com.heikinashi.monitoring.domain.strategy.StrategyRepository strategies,
            StrategyChartRenderer chartRenderer,
            HaRepository haRepository,
            AiAnalyst aiAnalyst,
            EmailSender emailSender,
            PendingStrategyAlertRepository pendingAlerts,
            AlertAuditRepository auditRepo,
            Clock clock,
            RetryConfig retryConfig,
            AlertsConfig alertsConfig) {
        this.instruments = instruments;
        this.strategies = strategies;
        this.chartRenderer = chartRenderer;
        this.haRepository = haRepository;
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

        Optional<ChartImage> chart = reRenderChart(pending);

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

    /**
     * Re-render the chart from the persisted strategy + bars. Empty when the
     * strategy was deleted since detection, or the render still fails (the send is
     * then chart-degraded).
     */
    private Optional<ChartImage> reRenderChart(PendingStrategyAlert pending) {
        StrategyAlert alert = pending.alert();
        Optional<Strategy> strategy = strategies.findByInstrumentId(alert.instrumentId());
        if (strategy.isEmpty()) {
            LOG.warn(
                    "strategy_retry_no_strategy instrument_id={} bar_time={} (chart-degraded)",
                    alert.instrumentId(),
                    alert.barTime());
            return Optional.empty();
        }
        List<HABar> bars = withTriggerBar(
                haRepository.findLastN(alert.instrumentId(), alert.timeframe(), alert.barTime(), CHART_LOOKBACK_BARS),
                alert.barTime(),
                pending.triggerBar());
        try {
            return Optional.of(chartRenderer.render(alert, strategy.get(), bars));
        } catch (ChartRenderException | DependencyUnavailableException e) {
            logRetryFailure(alert, "chart", e);
            return Optional.empty();
        }
    }

    /**
     * Ensure the triggering bar is in the series before rendering. Under
     * {@code SNAPSHOT_ONLY} retention a later ingest can evict the bar at
     * {@code barTime} before the retry runs, but heerwisch requires the entry/exit
     * marker to sit on a bar that is present (V7). When the lookback no longer
     * contains it, splice the persisted snapshot back in (ascending, no
     * duplicate) — mirroring the legacy {@code HeerwischChartRenderer} fallback.
     * Returns the input unchanged when the bar is present or no snapshot was kept.
     */
    static List<HABar> withTriggerBar(List<HABar> bars, Instant barTime, Optional<HABar> triggerBar) {
        boolean present = bars.stream().anyMatch(b -> b.barTime().equals(barTime));
        if (present || triggerBar.isEmpty()) {
            return bars;
        }
        List<HABar> restored = new ArrayList<>(bars);
        restored.add(triggerBar.get());
        restored.sort(Comparator.comparing(HABar::barTime));
        return restored;
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
            // The mail send itself failed (chart + AI already succeeded); record it
            // against the email/SES dependency, not AI, so backlog diagnostics point
            // at the right thing.
            return bumpRetry(pending, e.code(), "email", result);
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
            // SES responded but rejected every recipient. SES being DOWN is the
            // transient case handled above (exception -> bump). Here the rejection is
            // a permanent, invalid recipient list: on the final attempt, drop the
            // poison item instead of bumping it forever (CLAUDE.md §9 Component 1c).
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
            return bumpRetry(pending, "SES_REJECTED", "email", result);
        }

        // Delete BEFORE auditing: the email is already delivered, so the pending
        // must not survive a transient audit-write failure (it would be retried and
        // re-send a duplicate). Audit is best-effort and never blocks the delete.
        pendingAlerts.delete(pending.eventUid());
        if (auditEnabled) {
            try {
                auditRepo.recordSentStrategyAlert(alert, enrichment, delivered, messageIds, clock.instant());
            } catch (RuntimeException e) {
                LOG.error(
                        "strategy_retry_audit_failed instrument_id={} bar_time={} (email already sent, pending deleted)",
                        alert.instrumentId(),
                        alert.barTime(),
                        e);
            }
        }
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

    // Bump for a chart/AI re-render failure (derives the code/component from which
    // stage failed). SES failures use the explicit overload below so a mail outage
    // is not mislabeled as an AI failure in retry diagnostics.
    private PollResult bumpRetry(
            PendingStrategyAlert pending, boolean chartFailed, boolean aiFailed, PollResult result) {
        String component = chartFailed && aiFailed ? "chart+ai" : (chartFailed ? "chart" : "ai");
        String code = chartFailed ? "CHART_RENDER_FAILED" : "LLM_ERROR";
        return bumpRetry(pending, code, component, result);
    }

    private PollResult bumpRetry(PendingStrategyAlert pending, String code, String component, PollResult result) {
        Instant now = clock.instant();
        PendingStrategyAlert next = pending.bumped(
                now.plus(retryDelay),
                new PendingAlert.LastError(code, "transient failure on " + component, now, Optional.of(component)));
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
