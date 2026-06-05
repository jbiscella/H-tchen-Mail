package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.application.config.RetryConfig;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
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
 * {@link PendingStrategyAlert} that <b>stores the already-rendered chart bytes</b>
 * (when the chart had rendered) so the {@link StrategyRetryPollerService} never
 * reconstructs {@code Strategy} + bars. A chart-stage failure stores no bytes;
 * any later send is then chart-degraded.
 */
@Singleton
public class StrategyAlertDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(StrategyAlertDispatchService.class);

    private final InstrumentRepository instruments;
    private final StrategyChartRenderer chartRenderer;
    private final AiAnalyst aiAnalyst;
    private final EmailSender emailSender;
    private final PendingStrategyAlertRepository pendingAlerts;
    private final Clock clock;
    private final Duration retryDelay;

    public StrategyAlertDispatchService(
            InstrumentRepository instruments,
            StrategyChartRenderer chartRenderer,
            AiAnalyst aiAnalyst,
            EmailSender emailSender,
            PendingStrategyAlertRepository pendingAlerts,
            Clock clock,
            RetryConfig retryConfig) {
        this.instruments = instruments;
        this.chartRenderer = chartRenderer;
        this.aiAnalyst = aiAnalyst;
        this.emailSender = emailSender;
        this.pendingAlerts = pendingAlerts;
        this.clock = clock;
        this.retryDelay = retryConfig.delay();
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
            // The chart cannot be re-rendered in the poller (no Strategy + bars
            // there), so enqueue with no stored chart; the poller will retry
            // AI + email and ultimately send a chart-degraded email.
            return enqueue(alert, Optional.empty(), "chart", e);
        }

        AiAnalysis analysis;
        try {
            analysis = aiAnalyst.analyze(alert);
        } catch (LLMException | DependencyUnavailableException e) {
            return enqueue(alert, Optional.of(chart), "ai", e);
        }

        List<EmailSender.DeliveryResult> deliveries;
        try {
            deliveries = emailSender.sendFull(alert, chart, analysis, recipients);
        } catch (DependencyUnavailableException e) {
            return enqueue(alert, Optional.of(chart), "email", e);
        }

        boolean anyDelivered = deliveries.stream().anyMatch(EmailSender.DeliveryResult::delivered);
        if (!anyDelivered) {
            return enqueue(
                    alert, Optional.of(chart), "email", new DependencyUnavailableException("ses-all-rejected", null));
        }
        return DispatchSummary.empty().plusSent();
    }

    private DispatchSummary enqueue(
            StrategyAlert alert, Optional<ChartImage> chart, String component, RuntimeException cause) {
        Instant now = clock.instant();
        PendingStrategyAlert pending = new PendingStrategyAlert(
                PendingStrategyAlert.uidOf(alert),
                alert,
                chart,
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
                "strategy_dispatch_failed instrument_id={} bar_time={} component={} chart_stored={} code={} retry_at={}",
                alert.instrumentId(),
                alert.barTime(),
                component,
                chart.isPresent(),
                pending.lastError().code(),
                pending.retryAt(),
                cause);
        return DispatchSummary.empty().plusQueued();
    }
}
