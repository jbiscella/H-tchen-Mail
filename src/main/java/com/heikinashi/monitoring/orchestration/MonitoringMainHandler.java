package com.heikinashi.monitoring.orchestration;

import com.heikinashi.monitoring.application.MonitoringRunService;
import com.heikinashi.monitoring.domain.MainInput;
import com.heikinashi.monitoring.domain.MainSummary;
import io.micronaut.function.aws.MicronautRequestHandler;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AWS Lambda entry point for {@code monitoring-main} (CLAUDE.md §10).
 *
 * <p>EventBridge sends an empty payload at 22:00 UTC daily. Operators can
 * also invoke manually with {@code {"instrument_ids":["abc-123",...]}} to
 * process only the listed instruments. The handler delegates all real work
 * to {@link MonitoringRunService} and returns the structured summary as a
 * map for CloudWatch / log subscribers.
 */
public class MonitoringMainHandler extends MicronautRequestHandler<Map<String, Object>, Map<String, Object>> {

    @Inject
    MonitoringRunService runService;

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        MainInput mainInput = MainInputParser.parse(input);
        MainSummary summary = runService.execute(mainInput);
        return summaryToMap(summary);
    }

    private static Map<String, Object> summaryToMap(MainSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instruments_processed", s.instrumentsProcessed());
        m.put("instruments_succeeded", s.instrumentsSucceeded());
        m.put("instruments_failed", s.instrumentsFailed());
        m.put("bars_inserted", s.barsInserted());
        m.put("events_detected", s.eventsDetected());
        m.put("alerts_sent", s.alertsSent());
        m.put("alerts_queued", s.alertsQueued());
        m.put("alerts_skipped", s.alertsSkipped());
        m.put("duration_ms", s.durationMs());
        m.put("soft_timeout_hit", s.softTimeoutHit());
        return m;
    }

    @SuppressWarnings("unused")
    private static Optional<String> stringField(Map<String, Object> input, String key) {
        Object v = input == null ? null : input.get(key);
        return v == null ? Optional.empty() : Optional.of(v.toString());
    }
}
