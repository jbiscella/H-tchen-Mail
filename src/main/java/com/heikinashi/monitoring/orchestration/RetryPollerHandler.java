package com.heikinashi.monitoring.orchestration;

import com.heikinashi.monitoring.application.RetryPollerService;
import com.heikinashi.monitoring.application.StrategyRetryPollerService;
import com.heikinashi.monitoring.domain.PollResult;
import com.heikinashi.monitoring.infrastructure.BuildInfo;
import io.micronaut.function.aws.MicronautRequestHandler;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWS Lambda entry point for {@code retry-poller} (CLAUDE.md §10).
 *
 * <p>EventBridge fires every 15 minutes; the handler runs both retry queues:
 * the legacy {@code PENDING_ALERT} batch via {@link RetryPollerService} then the
 * {@code STRATEGY_PENDING_ALERT} batch via {@link StrategyRetryPollerService}
 * (SI-3c.3). Each due item is retried and either deleted or bumped. Returns the
 * structured summary (legacy + strategy counts) for CloudWatch.
 */
public class RetryPollerHandler extends MicronautRequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPollerHandler.class);

    @Inject
    RetryPollerService pollerService;

    @Inject
    StrategyRetryPollerService strategyPollerService;

    @Inject
    BuildInfo buildInfo;

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        LOG.info("build_info run=retry-poller build={}", buildInfo.label());
        // Each queue runs in its own guarded block so a runtime failure draining
        // one (e.g. a corrupt pending item) does not abort the run before the
        // other is serviced (CLAUDE.md §9 Component 1c "Handler isolation").
        PollResult result = runGuarded("legacy", pollerService::processBatch);
        PollResult strategyResult = runGuarded("strategy", strategyPollerService::processBatch);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("processed", result.processed());
        m.put("sent_full", result.sentFull());
        m.put("sent_degraded", result.sentDegraded());
        m.put("requeued", result.requeued());
        m.put("strategy_processed", strategyResult.processed());
        m.put("strategy_sent_full", strategyResult.sentFull());
        m.put("strategy_sent_degraded", strategyResult.sentDegraded());
        m.put("strategy_requeued", strategyResult.requeued());
        return m;
    }

    private static PollResult runGuarded(String queue, java.util.function.Supplier<PollResult> batch) {
        try {
            return batch.get();
        } catch (RuntimeException e) {
            LOG.error(
                    "retry_poller_queue_failed queue={} ex_class={} message={}",
                    queue,
                    e.getClass().getName(),
                    e.getMessage(),
                    e);
            return PollResult.empty();
        }
    }
}
