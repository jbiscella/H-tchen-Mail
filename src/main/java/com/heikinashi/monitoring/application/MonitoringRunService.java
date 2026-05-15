package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.application.config.RunConfig;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.DispatchSummary;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.MainInput;
import com.heikinashi.monitoring.domain.MainSummary;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.OhlcRepository;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.DomainException;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final OhlcRepository ohlcRepository;
    private final HaRepository haRepository;
    private final Clock clock;
    private final Duration softTimeout;

    public MonitoringRunService(
            InstrumentRepository instruments,
            IngestionService ingestionService,
            HeikinAshiService heikinAshiService,
            PatternDetectionService detectionService,
            AlertDispatchService dispatchService,
            OhlcRepository ohlcRepository,
            HaRepository haRepository,
            Clock clock,
            RunConfig runConfig) {
        this.instruments = instruments;
        this.ingestionService = ingestionService;
        this.heikinAshiService = heikinAshiService;
        this.detectionService = detectionService;
        this.dispatchService = dispatchService;
        this.ohlcRepository = ohlcRepository;
        this.haRepository = haRepository;
        this.clock = clock;
        this.softTimeout = runConfig.softTimeout();
    }

    public MainSummary execute(MainInput input) {
        String traceId = UUID.randomUUID().toString();
        long t0 = clock.millis();
        long deadline = t0 + softTimeout.toMillis();

        List<Instrument> targets = resolveTargets(input);
        MainSummary summary = MainSummary.empty();

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
            List<PatternEvent> instrumentEvents = new ArrayList<>();
            Map<Timeframe, Boolean> realEventForTf = new LinkedHashMap<>();
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
                    List<PatternEvent> raw = detectionService.detectPatterns(inst, entry.getKey(), haBars);
                    List<PatternEvent> events = LatestBarEventFilter.keepLatestBarOnly(raw);
                    int suppressed = raw.size() - events.size();
                    if (suppressed > 0) {
                        LOG.info(
                                "main_events_suppressed instrument_id={} timeframe={} kept={} suppressed={}",
                                inst.id(),
                                entry.getKey().wire(),
                                events.size(),
                                suppressed);
                    }
                    instrumentEvents.addAll(events);
                    summary = summary.addEvents(events.size());
                    realEventForTf.merge(entry.getKey(), !events.isEmpty(), Boolean::logicalOr);
                }

                // force_email escape hatch: for every tracked timeframe that did
                // NOT produce a real event this run, synthesize a single forced
                // event from the latest persisted HA + OHLC so the chart + AI
                // + email pipeline runs end-to-end. Skips silently if there's
                // no persisted HA bar yet (first-ever ingest for that
                // instrument).
                if (input.forceEmail()) {
                    InstrumentConfig cfg = instruments
                            .findConfigById(inst.id())
                            .orElseThrow(() -> new IllegalStateException("missing config for " + inst.id()));
                    for (Timeframe tf : cfg.trackedTimeframes()) {
                        if (Boolean.TRUE.equals(realEventForTf.get(tf))) continue;
                        Optional<PatternEvent> forced = buildForcedEvent(inst, tf);
                        if (forced.isPresent()) {
                            instrumentEvents.add(forced.get());
                            summary = summary.addEvents(1);
                            LOG.info(
                                    "main_forced_event instrument_id={} timeframe={} bar_time={}",
                                    inst.id(),
                                    tf.wire(),
                                    forced.get().barTime());
                        } else {
                            LOG.warn(
                                    "main_forced_event_skipped instrument_id={} timeframe={} reason=no_ha_bar",
                                    inst.id(),
                                    tf.wire());
                        }
                    }
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

            // Dispatch this instrument's alerts now, while we're still in scope.
            // Per-instrument dispatch keeps memory flat (no run-wide accumulator)
            // and ensures the soft-timeout cuts cleanly: every successfully
            // processed instrument has its alerts attempted before we ever
            // consider abandoning the loop.
            if (!instrumentEvents.isEmpty()) {
                DispatchSummary dispatchSummary = dispatchService.dispatchAlerts(instrumentEvents);
                summary = summary.withDispatch(dispatchSummary);
            }
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

    /**
     * Synthesise a PatternEvent labelled FORCED/FORCED so the chart + AI +
     * email pipeline can be exercised end-to-end without waiting for a real
     * pattern. Reads the latest HA bar (and matching OHLC) from persistence
     * — when there isn't one yet (first ingest for the instrument) the
     * call returns empty and the caller logs + skips.
     */
    private Optional<PatternEvent> buildForcedEvent(Instrument inst, Timeframe tf) {
        Instant cutoff = clock.instant().plusSeconds(1);
        Optional<HABar> latestHa = haRepository.findLatestBefore(inst.id(), tf, cutoff);
        if (latestHa.isEmpty()) {
            return Optional.empty();
        }
        HABar ha = latestHa.get();
        List<OHLCBar> matching = ohlcRepository.findRange(inst.id(), tf, ha.barTime(), ha.barTime());
        if (matching.isEmpty()) {
            return Optional.empty();
        }
        OHLCBar ohlc = matching.get(0);
        BarSnapshot snapshot = new BarSnapshot(
                ohlc.open(),
                ohlc.high(),
                ohlc.low(),
                ohlc.close(),
                ohlc.volume(),
                ha.haOpen(),
                ha.haHigh(),
                ha.haLow(),
                ha.haClose());
        return Optional.of(new PatternEvent(
                inst.id(),
                inst.ticker(),
                inst.exchange(),
                tf,
                ha.barTime(),
                PatternKind.FORCED,
                PatternSubtype.FORCED,
                Map.of("forced", "true"),
                snapshot,
                clock.instant()));
    }
}
