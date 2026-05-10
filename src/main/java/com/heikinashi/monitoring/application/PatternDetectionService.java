package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.OhlcRepository;
import com.heikinashi.monitoring.domain.PatternDetector;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.PatternsConfig;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
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
 * Block 5 — HA pattern detection (CLAUDE.md §8).
 *
 * <p>Reads the streak-history slice in a single Query (sized to the maximum
 * needed across enabled patterns), then runs {@link PatternDetector} per new
 * HA bar emitting one {@link PatternEvent} per matched pattern. The service
 * itself is a pure orchestrator — no DB writes.
 */
@Singleton
public class PatternDetectionService {

    private static final Logger LOG = LoggerFactory.getLogger(PatternDetectionService.class);

    private final InstrumentRepository instruments;
    private final OhlcRepository ohlc;
    private final HaRepository ha;
    private final Clock clock;

    public PatternDetectionService(
            InstrumentRepository instruments, OhlcRepository ohlc, HaRepository ha, Clock clock) {
        this.instruments = instruments;
        this.ohlc = ohlc;
        this.ha = ha;
        this.clock = clock;
    }

    public List<PatternEvent> detectPatterns(Instrument instrument, Timeframe tf, List<HABar> newHaBars) {
        if (newHaBars.isEmpty()) {
            return List.of();
        }
        InstrumentConfig cfg = instruments
                .findConfigById(instrument.id())
                .orElseThrow(() -> new InstrumentNotFoundException(instrument.id()));
        PatternsConfig patterns = cfg.patterns();

        if (!patterns.colorChange().enabled()
                && !patterns.strongCandle().enabled()
                && !patterns.doji().enabled()) {
            return List.of();
        }

        List<HABar> sortedNew = new ArrayList<>(newHaBars);
        sortedNew.sort(Comparator.comparing(HABar::barTime));
        Instant earliest = sortedNew.get(0).barTime();
        Instant latest = sortedNew.get(sortedNew.size() - 1).barTime();

        // K = max(min_streak_length over enabled color_change patterns) + M
        int k = 0;
        if (patterns.colorChange().enabled()) {
            k = Math.max(k, patterns.colorChange().minStreakLength());
        }
        k += sortedNew.size();

        List<HABar> historyBefore = ha.findLastNBefore(instrument.id(), tf, earliest, k);

        // Working chain: history immediately before the earliest new bar, then the new bars in order.
        List<HABar> chain = new ArrayList<>(historyBefore.size() + sortedNew.size());
        chain.addAll(historyBefore);
        chain.addAll(sortedNew);

        // Pre-fetch OHLC bars across the new-bar window so we can build BarSnapshots.
        // Bound the read by the latest new HA bar — under FULL_HISTORY an
        // unbounded lower-only query reads every bar ever stored for the
        // instrument, even though we only need the window we just computed HA
        // for.
        List<OHLCBar> ohlcRange = ohlc.findRange(instrument.id(), tf, earliest, latest);
        Map<Instant, OHLCBar> ohlcByTime = new HashMap<>();
        for (OHLCBar bar : ohlcRange) {
            ohlcByTime.put(bar.barTime(), bar);
        }

        Instant detectedAt = clock.instant();
        List<PatternEvent> events = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            HABar bar = chain.get(i);
            if (bar.barTime().isBefore(earliest)) {
                continue; // history-only bars don't emit events
            }
            OHLCBar ohlcBar = ohlcByTime.get(bar.barTime());
            if (ohlcBar == null) {
                LOG.warn("ohlc_missing_for_ha instrument_id={} bar_time={}", instrument.id(), bar.barTime());
                continue;
            }

            if (patterns.colorChange().enabled()) {
                List<HABar> prev = chain.subList(0, i);
                Optional<PatternSubtype> hit = PatternDetector.detectColorChange(
                        bar, prev, patterns.colorChange().minStreakLength());
                hit.ifPresent(s -> events.add(buildEvent(
                        instrument,
                        tf,
                        bar,
                        ohlcBar,
                        PatternKind.COLOR_CHANGE,
                        s,
                        Map.of("min_streak_length", patterns.colorChange().minStreakLength()),
                        detectedAt)));
            }
            if (patterns.strongCandle().enabled()) {
                Optional<PatternSubtype> hit = PatternDetector.detectStrongCandle(
                        bar,
                        patterns.strongCandle().wickTolerance(),
                        patterns.strongCandle().minBodyRatio());
                hit.ifPresent(s -> events.add(buildEvent(
                        instrument,
                        tf,
                        bar,
                        ohlcBar,
                        PatternKind.STRONG_CANDLE,
                        s,
                        Map.of(
                                "wick_tolerance", patterns.strongCandle().wickTolerance(),
                                "min_body_ratio", patterns.strongCandle().minBodyRatio()),
                        detectedAt)));
            }
            if (patterns.doji().enabled()) {
                Optional<PatternSubtype> hit =
                        PatternDetector.detectDoji(bar, patterns.doji().maxBodyRatio());
                hit.ifPresent(s -> events.add(buildEvent(
                        instrument,
                        tf,
                        bar,
                        ohlcBar,
                        PatternKind.DOJI,
                        s,
                        Map.of("max_body_ratio", patterns.doji().maxBodyRatio()),
                        detectedAt)));
            }
        }
        return events;
    }

    private PatternEvent buildEvent(
            Instrument instrument,
            Timeframe tf,
            HABar ha,
            OHLCBar ohlc,
            PatternKind kind,
            PatternSubtype subtype,
            Map<String, Object> params,
            Instant detectedAt) {
        return new PatternEvent(
                instrument.id(),
                instrument.ticker(),
                instrument.exchange(),
                tf,
                ha.barTime(),
                kind,
                subtype,
                params,
                BarSnapshot.from(ohlc, ha),
                detectedAt);
    }
}
