package com.heikinashi.monitoring.infrastructure.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Blocks 15-16 — strategy evaluation via dsl-eval over the raw OHLC series. */
class DslStrategyDetectorTest {

    private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant DETECTED = Instant.parse("2026-05-07T22:00:00Z");

    private final DslStrategyDetector detector = new DslStrategyDetector();
    private final Instrument instrument = new Instrument(
            "abc-123", "AAPL", "NASDAQ", Optional.empty(), Optional.empty(), InstrumentStatus.ACTIVE, T0, T0);

    private static OHLCBar bar(int day, double close) {
        BigDecimal c = BigDecimal.valueOf(close);
        return new OHLCBar(
                "abc-123",
                Timeframe.D1,
                T0.plus(day, ChronoUnit.DAYS),
                c,
                c.add(BigDecimal.ONE),
                c.subtract(BigDecimal.ONE),
                c,
                Optional.of(BigDecimal.valueOf(1000)),
                "test",
                DETECTED);
    }

    private static List<OHLCBar> closes(double... closes) {
        List<OHLCBar> bars = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            bars.add(bar(i, closes[i]));
        }
        return bars;
    }

    private static StrategyScenario scenario(String name, String role, String... conditions) {
        return new StrategyScenario(
                name,
                role,
                List.of(conditions),
                Optional.of("flat"),
                Optional.of("entry * 0.98"),
                Optional.of("entry * 1.05"));
    }

    @Test
    void fires_when_a_scenario_goes_false_to_true_on_the_latest_bar() {
        List<OHLCBar> series = closes(99, 99, 99, 105); // crosses above 100 on the last bar
        Strategy strategy = new Strategy("s", List.of(scenario("entry", "long_entry", "close is above 100")));

        Optional<StrategyAlert> alert = detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED);

        assertThat(alert).isPresent();
        assertThat(alert.get().barTime()).isEqualTo(series.get(3).barTime());
        assertThat(alert.get().lines()).hasSize(1);
        assertThat(alert.get().lines().get(0).role()).isEqualTo("long_entry");
        assertThat(alert.get().lines().get(0).stopLoss()).contains("entry * 0.98");
    }

    @Test
    void does_not_fire_when_the_condition_is_false_on_the_latest_bar() {
        List<OHLCBar> series = closes(99, 105, 105, 99);
        Strategy strategy = new Strategy("s", List.of(scenario("entry", "long_entry", "close is above 100")));
        assertThat(detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED))
                .isEmpty();
    }

    @Test
    void transition_gating_suppresses_a_condition_already_true_on_the_previous_bar() {
        List<OHLCBar> series = closes(99, 101, 105); // already > 100 on the previous bar
        Strategy strategy = new Strategy("s", List.of(scenario("entry", "long_entry", "close is above 100")));
        assertThat(detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED))
                .isEmpty();
    }

    @Test
    void multiple_scenarios_matching_the_latest_bar_produce_one_alert_with_one_line_each() {
        List<OHLCBar> series = closes(99, 99, 105);
        Strategy strategy = new Strategy(
                "s",
                List.of(
                        scenario("a", "long_entry", "close is above 100"),
                        scenario("b", "long_entry_2", "close is above 101")));

        Optional<StrategyAlert> alert = detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED);

        assertThat(alert).isPresent();
        assertThat(alert.get().lines()).extracting(l -> l.scenarioName()).containsExactly("a", "b");
    }

    @Test
    void conjunction_requires_all_conditions_true() {
        List<OHLCBar> series = closes(99, 99, 105);
        // close>100 true, but close>200 false -> scenario does not fire.
        Strategy strategy =
                new Strategy("s", List.of(scenario("entry", "long_entry", "close is above 100", "close is above 200")));
        assertThat(detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED))
                .isEmpty();
    }

    @Test
    void evaluation_is_deterministic() {
        List<OHLCBar> series = closes(99, 99, 99, 105);
        Strategy strategy = new Strategy("s", List.of(scenario("entry", "long_entry", "close is above 100")));
        Optional<StrategyAlert> first = detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED);
        Optional<StrategyAlert> second = detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void empty_series_yields_no_alert() {
        Strategy strategy = new Strategy("s", List.of(scenario("entry", "long_entry", "close is above 100")));
        assertThat(detector.evaluateLatest(instrument, Timeframe.D1, strategy, List.of(), DETECTED))
                .isEmpty();
    }
}
