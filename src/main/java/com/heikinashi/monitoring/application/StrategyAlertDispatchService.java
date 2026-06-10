package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.application.config.AlertsConfig;
import com.heikinashi.monitoring.application.config.RetryConfig;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.AlertAuditRepository;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.DispatchSummary;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlertRepository;
import com.heikinashi.monitoring.domain.StrategyChartRenderer;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.DomainException;
import com.heikinashi.monitoring.domain.error.LLMException;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
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
 * SI-3c — dedicated dispatch of a {@link StrategyAlert} (CLAUDE.md §9 Component
 * 1c), the faithful "path A": render the strategy chart (SI-3c.1) → run the AI
 * analyst for the alert → send the full email, preserving every matched line /
 * role / memo.
 *
 * <p>SI-3c.2 was the first-attempt happy path + no-recipients skip. SI-3c.3 adds
 * retry: on a transient failure the alert is enqueued as a
 * {@link PendingStrategyAlert} that stores <b>only the alert + retry bookkeeping,
 * never the chart</b>. Because the {@code Strategy} is persisted and bars are
 * readable by count, the {@link StrategyRetryPollerService} <b>re-renders</b> the
 * chart from {@code Strategy} + bars on retry rather than carrying a
 * (potentially &gt;400&nbsp;KB) PNG blob in the row.
 */
@Singleton
public class StrategyAlertDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(StrategyAlertDispatchService.class);

    private final InstrumentRepository instruments;
    private final StrategyChartRenderer chartRenderer;
    private final AiAnalyst aiAnalyst;
    private final EmailSender emailSender;
    private final PendingStrategyAlertRepository pendingAlerts;
    private final AlertAuditRepository auditRepo;
    private final Clock clock;
    private final Duration retryDelay;
    private final boolean auditEnabled;

    public StrategyAlertDispatchService(
            InstrumentRepository instruments,
            StrategyChartRenderer chartRenderer,
            AiAnalyst aiAnalyst,
            EmailSender emailSender,
            PendingStrategyAlertRepository pendingAlerts,
            AlertAuditRepository auditRepo,
            Clock clock,
            RetryConfig retryConfig,
            AlertsConfig alertsConfig) {
        this.instruments = instruments;
        this.chartRenderer = chartRenderer;
        this.aiAnalyst = aiAnalyst;
        this.emailSender = emailSender;
        this.pendingAlerts = pendingAlerts;
        this.auditRepo = auditRepo;
        this.clock = clock;
        this.retryDelay = retryConfig.delay();
        this.auditEnabled = alertsConfig.isAuditEnabled();
    }

    public DispatchSummary dispatch(StrategyAlert alert, Strategy strategy, List<HABar> bars) {
        InstrumentConfig cfg = instruments.findConfigById(alert.instrumentId()).orElse(null);
        if (cfg == null) {
            LOG.warn("strategy_dispatch_skip_no_config instrument_id={}", alert.instrumentId());
            return DispatchSummary.empty().plusFailed();
        }
        Set<String> recipients = cfg.recipients();
        if (recipients.isEmpty()) {
            LOG.warn(
                    "strategy_dispatch_skip_no_recipients instrument_id={} bar_time={}",
                    alert.instrumentId(),
                    alert.barTime());
            return DispatchSummary.empty().plusSkipped();
        }

        ChartImage chart;
        try {
            chart = chartRenderer.render(alert, strategy, bars);
        } catch (ChartRenderException | DependencyUnavailableException e) {
            return enqueue(alert, strategy, bars, "chart", e);
        }

        AiAnalysis analysis;
        try {
            analysis = aiAnalyst.analyze(alert);
        } catch (LLMException | DependencyUnavailableException e) {
            return enqueue(alert, strategy, bars, "ai", e);
        }

        List<EmailSender.DeliveryResult> deliveries;
        try {
            deliveries = emailSender.sendFull(alert, chart, analysis, recipients);
        } catch (DependencyUnavailableException e) {
            return enqueue(alert, strategy, bars, "email", e);
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
            return enqueue(
                    alert, strategy, bars, "email", new DependencyUnavailableException("ses-all-rejected", null));
        }
        if (auditEnabled) {
            auditRepo.recordSentStrategyAlert(alert, AlertEnrichment.FULL, delivered, messageIds, clock.instant());
        }
        return DispatchSummary.empty().plusSent();
    }

    private DispatchSummary enqueue(
            StrategyAlert alert, Strategy strategy, List<HABar> bars, String component, RuntimeException cause) {
        // No chart is stored: the retry poller re-renders from bars (CLAUDE.md §9
        // Component 1c SI-3c.3). We DO snapshot (a) the firing strategy, so the
        // retry renders the rules that actually fired even if the live STRATEGY
        // item is later edited/deleted, and (b) the triggering HA bar, so the
        // retry can synthesize it back if retention evicts it (heerwisch V7 —
        // marker must be on a bar in the series).
        Instant now = clock.instant();
        Optional<HABar> triggerBar =
                bars.stream().filter(b -> b.barTime().equals(alert.barTime())).findFirst();
        PendingStrategyAlert pending = new PendingStrategyAlert(
                PendingStrategyAlert.uidOf(alert),
                alert,
                Optional.of(strategy),
                triggerBar,
                0,
                now.plus(retryDelay),
                new PendingAlert.LastError(
                        cause instanceof DomainException de
                                ? de.code()
                                : cause.getClass().getSimpleName(),
                        cause.getMessage() == null ? "" : cause.getMessage(),
                        now,
                        Optional.of(component)),
                now);
        pendingAlerts.enqueue(pending);
        LOG.error(
                "strategy_dispatch_failed instrument_id={} bar_time={} component={} code={} retry_at={}",
                alert.instrumentId(),
                alert.barTime(),
                component,
                pending.lastError().code(),
                pending.retryAt(),
                cause);
        return DispatchSummary.empty().plusQueued();
    }
}
