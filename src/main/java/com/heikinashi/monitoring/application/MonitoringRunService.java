package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.DispatchSummary;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.MainInput;
import com.heikinashi.monitoring.domain.MainSummary;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.DomainException;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block 7 — {@code monitoring-main} pipeline (CLAUDE.md §10).
 *
 * <p>Drives the full per-instrument flow: ingest → HA → detect → dispatch.
 * Iterates active instruments (or just the ids in {@link MainInput}),
 * isolates per-instrument failures, observes a soft timeout to stop
 * queueing new instruments before the Lambda cap, and emits a single
 * structured summary log line per run.
 */
@Singleton
public class MonitoringRunService {

    private static final Logger LOG = LoggerFactory.getLogger(MonitoringRunService.class);
    private static final int LIST_PAGE_SIZE = 100;

    private final InstrumentRepository instruments;
    private final IngestionService ingestionService;
    private final HeikinAshiService heikinAshiService;
    private final PatternDetectionService detectionService;
    private final AlertDispatchService dispatchService;
    private final Clock clock;
    private final Duration softTimeout;

    public MonitoringRunService(
            InstrumentRepository instruments,
            IngestionService ingestionService,
            HeikinAshiService heikinAshiService,
            PatternDetectionService detectionService,
            AlertDispatchService dispatchService,
            Clock clock,
            Duration softTimeout) {
        this.instruments = instruments;
        this.ingestionService = ingestionService;
        this.heikinAshiService = heikinAshiService;
        this.detectionService = detectionService;
        this.dispatchService = dispatchService;
        this.clock = clock;
        this.softTimeout = softTimeout;
    }

    public MainSummary execute(MainInput input) {
        String traceId = UUID.randomUUID().toString();
        long t0 = clock.millis();
        long deadline = t0 + softTimeout.toMillis();

        List<Instrument> targets = resolveTargets(input);
        MainSummary summary = MainSummary.empty();
        List<PatternEvent> queuedEvents = new ArrayList<>();

        for (Instrument inst : targets) {
            if (clock.millis() >= deadline) {
                LOG.warn(
                        "main_soft_timeout trace_id={} processed={} pending={}",
                        traceId,
                        summary.instrumentsProcessed(),
                        targets.size() - summary.instrumentsProcessed());
                summary = summary.withSoftTimeoutHit();
                break;
            }
            summary = summary.plusProcessed();
            try {
                Map<Timeframe, List<OHLCBar>> insertedByTf = ingestionService.ingestInstrument(inst);
                int barCount = 0;
                for (List<OHLCBar> bars : insertedByTf.values()) {
                    barCount += bars.size();
                }
                summary = summary.addBars(barCount);
                for (Map.Entry<Timeframe, List<OHLCBar>> entry : insertedByTf.entrySet()) {
                    if (entry.getValue().isEmpty()) continue;
                    List<HABar> haBars = heikinAshiService.computeFor(inst, entry.getKey(), entry.getValue());
                    List<PatternEvent> events = detectionService.detectPatterns(inst, entry.getKey(), haBars);
                    queuedEvents.addAll(events);
                    summary = summary.addEvents(events.size());
                }
                summary = summary.plusSucceeded();
            } catch (RuntimeException e) {
                summary = summary.plusFailed();
                LOG.error(
                        "main_instrument_failed trace_id={} instrument_id={} ticker={} code={} message={}",
                        traceId,
                        inst.id(),
                        inst.ticker(),
                        codeOf(e),
                        e.getMessage());
            }
        }

        if (!queuedEvents.isEmpty()) {
            DispatchSummary dispatchSummary = dispatchService.dispatchAlerts(queuedEvents);
            summary = summary.withDispatch(dispatchSummary);
        }

        summary = summary.withDuration(clock.millis() - t0);
        LOG.info(
                "main_run_summary trace_id={} run=main duration_ms={} instruments_processed={} "
                        + "instruments_succeeded={} instruments_failed={} bars_inserted={} events_detected={} "
                        + "alerts_sent={} alerts_queued={} soft_timeout_hit={}",
                traceId,
                summary.durationMs(),
                summary.instrumentsProcessed(),
                summary.instrumentsSucceeded(),
                summary.instrumentsFailed(),
                summary.barsInserted(),
                summary.eventsDetected(),
                summary.alertsSent(),
                summary.alertsQueued(),
                summary.softTimeoutHit());
        return summary;
    }

    private List<Instrument> resolveTargets(MainInput input) {
        if (input.instrumentIds().isPresent()) {
            return resolveById(input.instrumentIds().get());
        }
        return allActive();
    }

    private List<Instrument> resolveById(Set<String> ids) {
        List<Instrument> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Optional<Instrument> inst = instruments.findById(id);
            if (inst.isPresent()) {
                if (inst.get().status() == InstrumentStatus.ACTIVE) {
                    out.add(inst.get());
                } else {
                    LOG.warn("main_skip_archived instrument_id={}", id);
                }
            } else {
                LOG.warn("main_skip_unknown instrument_id={}", id);
            }
        }
        return out;
    }

    private List<Instrument> allActive() {
        List<Instrument> out = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        do {
            Page<Instrument> page = instruments.listByStatus(InstrumentStatus.ACTIVE, LIST_PAGE_SIZE, cursor);
            out.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor.isPresent());
        return out;
    }

    private static String codeOf(RuntimeException e) {
        if (e instanceof DomainException de) {
            return de.code();
        }
        return e.getClass().getSimpleName();
    }
}
