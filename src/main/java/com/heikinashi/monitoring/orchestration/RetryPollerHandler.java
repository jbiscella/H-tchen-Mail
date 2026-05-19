package com.heikinashi.monitoring.orchestration;

import com.heikinashi.monitoring.application.RetryPollerService;
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
 * <p>EventBridge fires every 15 minutes; the handler delegates to
 * {@link RetryPollerService#processBatch()} which queries due
 * {@code PENDING_ALERT} items, retries them, and either deletes or bumps
 * each one. Returns the structured summary for CloudWatch.
 */
public class RetryPollerHandler extends MicronautRequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPollerHandler.class);

    @Inject
    RetryPollerService pollerService;

    @Inject
    BuildInfo buildInfo;

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        LOG.info("build_info run=retry-poller build={}", buildInfo.label());
        PollResult result = pollerService.processBatch();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("processed", result.processed());
        m.put("sent_full", result.sentFull());
        m.put("sent_degraded", result.sentDegraded());
        m.put("requeued", result.requeued());
        return m;
    }
}
