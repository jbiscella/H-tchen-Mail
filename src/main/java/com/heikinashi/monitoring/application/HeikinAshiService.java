package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.BulkRecomputeResult;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.HeikinAshiCalculator;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.OhlcRepository;
import com.heikinashi.monitoring.domain.StoragePolicies;
import com.heikinashi.monitoring.domain.StoragePolicy;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block 4 — Heikin-Ashi computation (CLAUDE.md §7).
 *
 * <p>Incremental: given the OHLC bars just ingested, recompute HA from the
 * earliest of those forward, seeding from the HA bar immediately preceding
 * (or from OHLC values themselves if no prior HA exists). Idempotent —
 * identical OHLC produces identical HA, overwriting safely.
 *
 * <p>Bulk recompute: drop all HA bars and rebuild from the OHLC chain.
 */
@Singleton
public class HeikinAshiService {

    private static final Logger LOG = LoggerFactory.getLogger(HeikinAshiService.class);

    private final InstrumentRepository instruments;
    private final OhlcRepository ohlc;
    private final HaRepository ha;
    private final Clock clock;

    public HeikinAshiService(InstrumentRepository instruments, OhlcRepository ohlc, HaRepository ha, Clock clock) {
        this.instruments = instruments;
        this.ohlc = ohlc;
        this.ha = ha;
        this.clock = clock;
    }

    /**
     * Recompute HA bars for {@code (instrument, tf)} forward from the earliest
     * of {@code ingestedOhlc}. Returns the freshly persisted HA chain.
     */
    public List<HABar> computeFor(Instrument instrument, Timeframe tf, List<OHLCBar> ingestedOhlc) {
        if (ingestedOhlc.isEmpty()) {
            return List.of();
        }
        InstrumentConfig cfg = instruments
                .findConfigById(instrument.id())
                .orElseThrow(() -> new InstrumentNotFoundException(instrument.id()));

        Instant earliest = ingestedOhlc.stream()
                .map(OHLCBar::barTime)
                .min(Comparator.naturalOrder())
                .orElseThrow();

        Optional<HABar> prev = ha.findLatestBefore(instrument.id(), tf, earliest);
        List<OHLCBar> chain = new ArrayList<>(ohlc.findRange(instrument.id(), tf, earliest));
        chain.sort(Comparator.comparing(OHLCBar::barTime));
        if (chain.isEmpty()) {
            return List.of();
        }

        if (cfg.storagePolicy() == StoragePolicy.SNAPSHOT_ONLY && prev.isEmpty()) {
            // SNAPSHOT_ONLY truncated the previous HA before this run could read it.
            LOG.warn(
                    "ha_continuity_broken instrument_id={} timeframe={} bar_time={}",
                    instrument.id(),
                    tf.wire(),
                    earliest);
        }

        Instant computedAt = clock.instant();
        List<HABar> result = HeikinAshiCalculator.computeChain(prev, chain, computedAt);

        for (HABar bar : result) {
            Optional<Long> ttl = StoragePolicies.computeTtl(cfg, bar.barTime(), tf);
            if (cfg.storagePolicy() == StoragePolicy.SNAPSHOT_ONLY) {
                ha.snapshotReplace(instrument.id(), tf, bar, ttl);
            } else {
                ha.putBar(bar, ttl);
            }
        }
        return result;
    }

    public BulkRecomputeResult bulkRecompute(Instrument instrument, Timeframe tf) {
        long t0 = clock.millis();
        InstrumentConfig cfg = instruments
                .findConfigById(instrument.id())
                .orElseThrow(() -> new InstrumentNotFoundException(instrument.id()));

        ha.deleteAll(instrument.id(), tf);

        List<OHLCBar> all = new ArrayList<>(ohlc.findRange(instrument.id(), tf, Instant.EPOCH));
        all.sort(Comparator.comparing(OHLCBar::barTime));
        if (all.isEmpty()) {
            return new BulkRecomputeResult(0, 0, clock.millis() - t0);
        }

        Instant computedAt = clock.instant();
        List<HABar> chain = HeikinAshiCalculator.computeChain(Optional.empty(), all, computedAt);
        for (HABar bar : chain) {
            Optional<Long> ttl = StoragePolicies.computeTtl(cfg, bar.barTime(), tf);
            if (cfg.storagePolicy() == StoragePolicy.SNAPSHOT_ONLY) {
                ha.snapshotReplace(instrument.id(), tf, bar, ttl);
            } else {
                ha.putBar(bar, ttl);
            }
        }
        return new BulkRecomputeResult(all.size(), chain.size(), clock.millis() - t0);
    }
}
