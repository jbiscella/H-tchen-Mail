package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.application.config.AlertsConfig;
import com.heikinashi.monitoring.application.config.RetryConfig;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.AlertAuditRepository;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.OhlcRepository;
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
 * the AI analyst from the stored alert, then sends. Each due item is first
 * <b>claimed</b> (lease) via the conditional retry-count bump — only the claim
 * winner proceeds, so a double poller run sends exactly one email. On success it
 * records the audit item and deletes; under the cap it records {@code last_error}
 * in place (the claim already consumed the attempt); at the cap it sends a
 * degraded email and deletes. All recipients rejected on the final attempt drops
 * the poison item.
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
    private final OhlcRepository ohlcRepository;
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
            OhlcRepository ohlcRepository,
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
        this.ohlcRepository = ohlcRepository;
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
        // Claim-before-processing lease (CLAUDE.md §9 "Retry queue mechanics",
        // mirrored by SI-3c.3): the attempt is consumed up front by the
        // conditional bump, so a concurrent poller that read the same due
        // snapshot cannot also send — the loser of the claim is a complete
        // no-op. A crash after the claim leaves the item scheduled at retry_at
        // with one attempt consumed (at-least-once delivery preserved).
        Instant claimTime = clock.instant();
        PendingStrategyAlert claimed = pending.bumped(claimTime.plus(retryDelay), pending.lastError());
        if (!pendingAlerts.bumpRetry(claimed, pending.retryCount())) {
            LOG.info(
                    "strategy_retry_claim_lost_race instrument_id={} bar_time={}",
                    pending.alert().instrumentId(),
                    pending.alert().barTime());
            return result;
        }
        result = result.plusProcessed();
        StrategyAlert alert = claimed.alert();

        Set<String> recipients = recipientsOf(alert.instrumentId());
        if (recipients.isEmpty()) {
            LOG.warn(
                    "strategy_retry_skip_no_recipients instrument_id={} bar_time={}",
                    alert.instrumentId(),
                    alert.barTime());
            pendingAlerts.delete(claimed.eventUid());
            return result;
        }

        // One lookup, shared by the chart and the note, so both describe the SAME
        // strategy instance. Two independent lookups could disagree if the strategy were
        // re-imported between them (Block 18 / Codex review of PR #86).
        Optional<Strategy> strategy = strategies.findByInstrumentId(alert.instrumentId());
        if (strategy.isEmpty()) {
            LOG.warn(
                    "strategy_retry_no_strategy instrument_id={} bar_time={} (chart-degraded)",
                    alert.instrumentId(),
                    alert.barTime());
        }
        Optional<ChartImage> chart = strategy.flatMap(s -> reRenderChart(claimed, s));

        Optional<AiAnalysis> analysis;
        try {
            // Strategy gone => already chart-degraded; the note falls back to the bar series
            // with no indicators rather than inventing an indicator set.
            analysis = Optional.of(
                    strategy.map(s -> aiAnalyst.analyze(alert, s)).orElseGet(() -> aiAnalyst.analyze(alert)));
        } catch (LLMException | DependencyUnavailableException e) {
            logRetryFailure(alert, "ai", e);
            analysis = Optional.empty();
        }

        boolean lastAttempt = claimed.retryCount() >= maxAttempts;
        boolean fullyOk = chart.isPresent() && analysis.isPresent();

        if (fullyOk) {
            return sendAndFinish(claimed, alert, chart, analysis, recipients, false, result);
        }
        if (lastAttempt) {
            return sendAndFinish(claimed, alert, chart, analysis, recipients, true, result);
        }
        return recordFailure(claimed, chart.isEmpty(), analysis.isEmpty(), result);
    }

    /**
     * Re-render the chart from the persisted strategy + bars. Empty when the
     * strategy was deleted since detection, or the render still fails (the send is
     * then chart-degraded).
     */
    private Optional<ChartImage> reRenderChart(PendingStrategyAlert pending, Strategy resolved) {
        StrategyAlert alert = pending.alert();
        Optional<Strategy> strategy = Optional.of(resolved);
        List<OHLCBar> bars = withTriggerBar(
                ohlcRepository.findLastN(alert.instrumentId(), alert.timeframe(), alert.barTime(), CHART_LOOKBACK_BARS),
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
    static List<OHLCBar> withTriggerBar(List<OHLCBar> bars, Instant barTime, Optional<OHLCBar> triggerBar) {
        boolean present = bars.stream().anyMatch(b -> b.barTime().equals(barTime));
        if (present || triggerBar.isEmpty()) {
            return bars;
        }
        List<OHLCBar> restored = new ArrayList<>(bars);
        restored.add(triggerBar.get());
        restored.sort(Comparator.comparing(OHLCBar::barTime));
        return restored;
    }

    private PollResult sendAndFinish(
            PendingStrategyAlert claimed,
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
            return recordFailure(claimed, e.code(), "email", result);
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
            // transient case handled above (exception -> record failure). Here the
            // rejection is a permanent, invalid recipient list: on the final attempt,
            // drop the poison item instead of requeueing it forever (CLAUDE.md §9
            // Component 1c).
            boolean lastAttempt = claimed.retryCount() >= maxAttempts;
            if (lastAttempt) {
                pendingAlerts.delete(claimed.eventUid());
                LOG.error(
                        "strategy_retry_dropped_all_rejected instrument_id={} bar_time={} retry_count={}",
                        alert.instrumentId(),
                        alert.barTime(),
                        claimed.retryCount());
                return result; // dropped (not sent, not requeued); already counted as processed
            }
            return recordFailure(claimed, "SES_REJECTED", "email", result);
        }

        // Delete BEFORE auditing: the email is already delivered, so the pending
        // must not survive a transient audit-write failure (it would be retried and
        // re-send a duplicate). Audit is best-effort and never blocks the delete.
        pendingAlerts.delete(claimed.eventUid());
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

    // Failure record for a chart/AI re-render failure (derives the code/component
    // from which stage failed). SES failures use the explicit overload below so a
    // mail outage is not mislabeled as an AI failure in retry diagnostics.
    private PollResult recordFailure(
            PendingStrategyAlert claimed, boolean chartFailed, boolean aiFailed, PollResult result) {
        String component = chartFailed && aiFailed ? "chart+ai" : (chartFailed ? "chart" : "ai");
        String code = chartFailed ? "CHART_RENDER_FAILED" : "LLM_ERROR";
        return recordFailure(claimed, code, component, result);
    }

    /**
     * Record the real failure on the already-claimed item: the claim consumed
     * the attempt (retry_count) and set retry_at, so this only replaces
     * last_error — conditional at the claimed count, no second increment.
     */
    private PollResult recordFailure(PendingStrategyAlert claimed, String code, String component, PollResult result) {
        Instant now = clock.instant();
        PendingStrategyAlert next = new PendingStrategyAlert(
                claimed.eventUid(),
                claimed.alert(),
                claimed.triggerBar(),
                claimed.retryCount(),
                claimed.retryAt(),
                new PendingAlert.LastError(code, "transient failure on " + component, now, Optional.of(component)),
                claimed.createdAt());
        boolean accepted = pendingAlerts.bumpRetry(next, claimed.retryCount());
        if (!accepted) {
            LOG.info(
                    "strategy_retry_error_write_lost_race instrument_id={} bar_time={}",
                    claimed.alert().instrumentId(),
                    claimed.alert().barTime());
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
