package com.heikinashi.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyDetector;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AAA unit tests for the strategy-evaluation series handed to the
 * {@link StrategyDetector} after the fresh-bar merge (CLAUDE.md Block 16, SI-3
 * read-consistency note): the merged series must stay capped at the documented
 * 300-bar lookback (trimmed from the oldest side) and must end at the freshly
 * ingested latest bar. Boundary sizes are not worth enumerating in Gherkin —
 * the orchestration scenario covers the lag behaviour; this covers the cap.
 */
class StrategyEvalLookbackCapTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Timeframe TF = Timeframe.D1;
    private static final int CAP = 300;

    /** Captures the series the detector is handed; never alerts. */
    private static final class CapturingDetector implements StrategyDetector {
        private List<OHLCBar> series;

        @Override
        public Optional<StrategyAlert> evaluateLatest(
                Instrument instrument, Timeframe tf, Strategy strategy, List<OHLCBar> ohlcSeries, Instant at) {
            this.series = ohlcSeries;
            return Optional.empty();
        }
    }

    @Test
    void merged_series_is_capped_at_the_lookback_and_ends_at_the_fresh_latest_bar() {
        // Arrange: 300 persisted bars, then an outage catch-up inserting 50 newer
        // bars the persisted read does not return (disjoint fresh tail).
        InMemoryOhlcRepository ohlc = new InMemoryOhlcRepository();
        for (int i = 0; i < 300; i++) {
            ohlc.putBar(bar(T0.plus(Duration.ofDays(i))), Optional.empty());
        }
        List<OHLCBar> fresh = new ArrayList<>();
        List<HABar> freshHa = new ArrayList<>();
        for (int i = 300; i < 350; i++) {
            Instant at = T0.plus(Duration.ofDays(i));
            fresh.add(bar(at));
            freshHa.add(haBar(at));
        }
        CapturingDetector detector = new CapturingDetector();
        PatternDetectionService service = service(ohlc, detector);

        // Act
        service.detectStrategyAlert(instrument(), TF, freshHa, fresh);

        // Assert: capped at 300, ending at the freshest bar (oldest side trimmed).
        Instant latest = T0.plus(Duration.ofDays(349));
        assertThat(detector.series).hasSize(CAP);
        assertThat(detector.series.get(CAP - 1).barTime()).isEqualTo(latest);
    }

    @Test
    void merged_series_below_the_cap_is_passed_through_whole() {
        // Arrange: 10 persisted bars; the same 10 also arrive as fresh (normal run).
        InMemoryOhlcRepository ohlc = new InMemoryOhlcRepository();
        List<OHLCBar> fresh = new ArrayList<>();
        List<HABar> freshHa = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant at = T0.plus(Duration.ofDays(i));
            OHLCBar b = bar(at);
            ohlc.putBar(b, Optional.empty());
            fresh.add(b);
            freshHa.add(haBar(at));
        }
        CapturingDetector detector = new CapturingDetector();
        PatternDetectionService service = service(ohlc, detector);

        // Act
        service.detectStrategyAlert(instrument(), TF, freshHa, fresh);

        // Assert: dedup by bar time, no spurious truncation.
        assertThat(detector.series).hasSize(10);
    }

    private static PatternDetectionService service(InMemoryOhlcRepository ohlc, StrategyDetector detector) {
        InMemoryStrategyRepository strategies = new InMemoryStrategyRepository();
        strategies.put(
                "inst-1",
                new Strategy(
                        "cap-test",
                        List.of(new StrategyScenario(
                                "entry",
                                "long_entry",
                                List.of("close is above 0"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()))));
        return new PatternDetectionService(
                new InMemoryInstrumentRepository(),
                ohlc,
                new InMemoryHaRepository(),
                strategies,
                detector,
                Clock.fixed(T0.plus(Duration.ofDays(400)), ZoneOffset.UTC));
    }

    private static Instrument instrument() {
        return new Instrument(
                "inst-1", "AAPL", "NASDAQ", Optional.empty(), Optional.empty(), InstrumentStatus.ACTIVE, T0, T0);
    }

    private static OHLCBar bar(Instant at) {
        return new OHLCBar(
                "inst-1",
                TF,
                at,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.empty(),
                "test",
                at);
    }

    private static HABar haBar(Instant at) {
        return new HABar(
                "inst-1",
                TF,
                at,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                at);
    }
}
