package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.IngestionSummary;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.MarketDataProvider;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.OhlcRepository;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.StoragePolicies;
import com.heikinashi.monitoring.domain.StoragePolicy;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.Timeframes;
import com.heikinashi.monitoring.domain.error.CircuitOpenException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import com.heikinashi.monitoring.domain.error.OHLCInvariantViolationException;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block 3 — Quote Ingestion (CLAUDE.md §6).
 *
 * <p>Pure orchestration: iterates active instruments, calls the
 * {@link MarketDataProvider} per (instrument, timeframe), normalises and
 * filters bars, persists via the {@link OhlcRepository} respecting the
 * storage policy. Honours a per-ticker circuit breaker (3 consecutive
 * failures → skip remainder of run) and emits a summary.
 */
@Singleton
public class IngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionService.class);
    private static final int LIST_PAGE_SIZE = 100;

    private final InstrumentRepository instruments;
    private final OhlcRepository ohlc;
    private final MarketDataProvider provider;
    private final Clock clock;
    private final IngestionConfig config;
    private final Sleeper sleeper;

    public IngestionService(
            InstrumentRepository instruments,
            OhlcRepository ohlc,
            MarketDataProvider provider,
            Clock clock,
            IngestionConfig config,
            Sleeper sleeper) {
        this.instruments = instruments;
        this.ohlc = ohlc;
        this.provider = provider;
        this.clock = clock;
        this.config = config;
        this.sleeper = sleeper;
    }

    public IngestionSummary ingestAllActive() {
        long t0 = clock.millis();
        IngestionSummary summary = IngestionSummary.empty();
        Map<String, Integer> consecutiveFailures = new HashMap<>();

        for (Instrument inst : allActive()) {
            summary = summary.plusProcessed();
            String tickerKey = inst.exchange() + "#" + inst.ticker();
            int prior = consecutiveFailures.getOrDefault(tickerKey, 0);
            if (prior >= config.circuitBreakerThreshold()) {
                LOG.warn("circuit_open ticker={} run_skip=true", inst.ticker());
                summary = summary.plusFailed();
                continue;
            }
            try {
                Map<Timeframe, List<OHLCBar>> inserted = ingestInstrument(inst);
                int barCount = 0;
                for (List<OHLCBar> tfBars : inserted.values()) {
                    barCount += tfBars.size();
                }
                summary = summary.plusSucceeded().plusInserted(barCount);
                consecutiveFailures.put(tickerKey, 0);
            } catch (CircuitOpenException e) {
                summary = summary.plusFailed();
            } catch (RuntimeException e) {
                consecutiveFailures.put(tickerKey, prior + 1);
                summary = summary.plusFailed();
                LOG.error(
                        "ingest_failed instrument_id={} ticker={} code={} message={}",
                        inst.id(),
                        inst.ticker(),
                        codeOf(e),
                        e.getMessage());
            }
        }

        if (summary.processed() > 0 && summary.failureRate() > config.failureRateAlertThreshold()) {
            LOG.error("HIGH_INGEST_FAILURE_RATE processed={} failed={}", summary.processed(), summary.failed());
        }

        return summary.withDuration(clock.millis() - t0);
    }

    /**
     * Ingest every tracked timeframe for {@code inst} and return the freshly
     * inserted bars by timeframe. The caller (Block 7 orchestrator) feeds
     * those into HA computation and pattern detection.
     */
    public Map<Timeframe, List<OHLCBar>> ingestInstrument(Instrument inst) {
        InstrumentConfig cfg =
                instruments.findConfigById(inst.id()).orElseThrow(() -> new InstrumentNotFoundException(inst.id()));

        Map<Timeframe, List<OHLCBar>> insertedByTf = new HashMap<>();
        for (Timeframe tf : cfg.trackedTimeframes()) {
            insertedByTf.put(tf, ingestTimeframe(inst, tf, cfg));
        }
        return insertedByTf;
    }

    public List<OHLCBar> ingestTimeframe(Instrument inst, Timeframe tf, InstrumentConfig cfg) {
        String symbol = config.providerSymbol(inst.ticker(), inst.exchange());
        Optional<OHLCBar> latest = ohlc.findLatest(inst.id(), tf);

        Instant since = latest.map(b -> b.barTime().plusSeconds(Timeframes.periodSeconds(tf)))
                .orElseGet(() ->
                        clock.instant().minusSeconds((long) config.bootstrapSize(tf) * Timeframes.periodSeconds(tf)));

        List<OHLCBar> raw = fetchWithRetry(symbol, tf, since);
        Instant now = clock.instant();
        List<OHLCBar> bars = new ArrayList<>();
        for (OHLCBar b : raw) {
            Instant normalized = Timeframes.normalizeBarTime(b.barTime(), tf);
            if (!Timeframes.isClosed(normalized, tf, now)) continue;
            OHLCBar prepared = b.withInstrumentId(inst.id()).withBarTime(normalized);
            try {
                prepared.validateInvariants();
            } catch (OHLCInvariantViolationException e) {
                LOG.warn(
                        "ohlc_invariant_violation instrument_id={} bar_time={} field={}",
                        inst.id(),
                        prepared.barTime(),
                        e.payload().get("field"));
                continue;
            }
            bars.add(prepared);
        }
        bars.sort(Comparator.comparing(OHLCBar::barTime));

        List<OHLCBar> inserted = new ArrayList<>();
        if (cfg.storagePolicy() == StoragePolicy.SNAPSHOT_ONLY) {
            for (OHLCBar bar : bars) {
                ohlc.snapshotReplace(inst.id(), tf, bar, StoragePolicies.computeTtl(cfg, bar.barTime(), tf));
                inserted.add(bar);
            }
        } else {
            for (OHLCBar bar : bars) {
                if (ohlc.putBar(bar, StoragePolicies.computeTtl(cfg, bar.barTime(), tf))) {
                    inserted.add(bar);
                }
            }
        }
        return inserted;
    }

    /**
     * Fetch history, retrying transient provider failures with exponential
     * backoff (CLAUDE.md §6 "Transient failure with retry"). Only
     * {@link ProviderUnavailableException} is retried — {@code TickerNotFound}
     * and {@code SchemaDrift} are not transient and propagate immediately. The
     * initial attempt plus {@code maxRetries} retries are made; backoff before
     * retry n is {@code retryBaseDelayMillis × 2^(n-1)} (e.g. 1s, 2s, 4s). On
     * exhaustion the last failure is re-thrown.
     */
    private List<OHLCBar> fetchWithRetry(String symbol, Timeframe tf, Instant since) {
        ProviderUnavailableException last = null;
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            if (attempt > 0) {
                long delay = config.retryBaseDelayMillis() << (attempt - 1);
                LOG.warn("ingest_retry symbol={} attempt={} delay_ms={}", symbol, attempt, delay);
                sleeper.sleepMillis(delay);
            }
            try {
                return provider.fetchHistory(symbol, tf, since);
            } catch (ProviderUnavailableException e) {
                last = e;
            }
        }
        throw last;
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
        if (e instanceof com.heikinashi.monitoring.domain.error.DomainException de) {
            return de.code();
        }
        return e.getClass().getSimpleName();
    }
}
