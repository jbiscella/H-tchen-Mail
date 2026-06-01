package com.heikinashi.monitoring.infrastructure.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.MaKind;
import com.heikinashi.monitoring.domain.strategy.MarketCondition;
import com.heikinashi.monitoring.domain.strategy.PriceField;
import com.heikinashi.monitoring.domain.strategy.Side;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Blocks 15-16 — strategy evaluation via nachtkrapp: matching, roles, memo, transition, multi-match, insufficient data. */
class NachtkrappStrategyDetectorTest {

    private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant DETECTED = Instant.parse("2026-05-07T22:00:00Z");

    private final NachtkrappStrategyDetector detector = new NachtkrappStrategyDetector();
    private final Instrument instrument = new Instrument(
            "abc-123", "AAPL", "NASDAQ", Optional.empty(), Optional.empty(), InstrumentStatus.ACTIVE, T0, T0);

    private static HABar bar(int day, String open, String close) {
        BigDecimal o = new BigDecimal(open);
        BigDecimal c = new BigDecimal(close);
        BigDecimal hi = o.max(c).add(BigDecimal.ONE);
        BigDecimal lo = o.min(c).subtract(BigDecimal.ONE);
        return new HABar("abc-123", Timeframe.D1, T0.plus(day, ChronoUnit.DAYS), o, hi, lo, c, DETECTED);
    }

    private static HABar red(int day) {
        return bar(day, "100", "98");
    }

    private static HABar green(int day) {
        return bar(day, "100", "102");
    }

    private static StrategyScenario colorChange(String name, String role, int minStreak, Side side) {
        return new StrategyScenario(
                name,
                role,
                List.of(new MarketCondition.ColorChange(minStreak, side)),
                Optional.of("flat"),
                Optional.of("entry * 0.98"),
                Optional.of("entry * 1.05"));
    }

    @Test
    void emits_one_alert_line_when_a_scenario_becomes_true_on_the_latest_bar() {
        // RED, RED, RED, GREEN -> bullish reversal at the latest (green) bar.
        List<HABar> series = List.of(red(0), red(1), red(2), green(3));
        Strategy strategy = new Strategy("rev", List.of(colorChange("entry", "long_entry", 3, Side.BULLISH)));

        Optional<StrategyAlert> alert = detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED);

        assertThat(alert).isPresent();
        StrategyAlert a = alert.get();
        assertThat(a.barTime()).isEqualTo(series.get(3).barTime());
        assertThat(a.ticker()).isEqualTo("AAPL");
        assertThat(a.lines()).hasSize(1);
        // Block 16: role label + memo quoted verbatim, never evaluated.
        assertThat(a.lines().get(0).role()).isEqualTo("long_entry");
        assertThat(a.lines().get(0).stopLoss()).contains("entry * 0.98");
        assertThat(a.lines().get(0).takeProfit()).contains("entry * 1.05");
        assertThat(a.lines().get(0).positionPrecondition()).contains("flat");
    }

    @Test
    void no_alert_when_the_scenario_did_not_become_true_on_the_latest_bar() {
        // The reversal is at index 3; the latest bar (index 4) is a continuation.
        List<HABar> series = List.of(red(0), red(1), red(2), green(3), green(4));
        Strategy strategy = new Strategy("rev", List.of(colorChange("entry", "long_entry", 3, Side.BULLISH)));

        assertThat(detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED))
                .isEmpty();
    }

    @Test
    void multiple_scenarios_matching_the_same_bar_produce_one_alert_with_one_line_each() {
        List<HABar> series = List.of(red(0), red(1), red(2), green(3));
        Strategy strategy = new Strategy(
                "multi",
                List.of(
                        colorChange("entry-strict", "long_entry", 3, Side.BULLISH),
                        colorChange("entry-loose", "long_entry_2", 2, Side.BULLISH)));

        Optional<StrategyAlert> alert = detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED);

        assertThat(alert).isPresent();
        assertThat(alert.get().lines()).hasSize(2);
        assertThat(alert.get().lines())
                .extracting(l -> l.scenarioName())
                .containsExactly("entry-strict", "entry-loose");
    }

    @Test
    void streak_too_short_does_not_match() {
        // Only two reds before the green; a min-streak-3 scenario must not fire.
        List<HABar> series = List.of(green(0), red(1), red(2), green(3));
        Strategy strategy = new Strategy("rev", List.of(colorChange("entry", "long_entry", 3, Side.BULLISH)));

        assertThat(detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED))
                .isEmpty();
    }

    @Test
    void insufficient_history_for_a_cross_condition_produces_no_alert() {
        // EMA(50)/SMA(200) cross needs ~201 bars; a 4-bar series can't satisfy it.
        List<HABar> series = List.of(red(0), red(1), red(2), green(3));
        StrategyScenario cross = new StrategyScenario(
                "golden",
                "long_entry",
                List.of(new MarketCondition.MovingAverageCross(
                        MaKind.EMA, 50, MaKind.SMA, 200, Side.BULLISH, PriceField.HA_CLOSE)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Strategy strategy = new Strategy("ma", List.of(cross));

        assertThat(detector.barsNeeded(strategy)).isEqualTo(201);
        assertThat(detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED))
                .isEmpty();
    }

    @Test
    void evaluation_is_deterministic() {
        List<HABar> series = List.of(red(0), red(1), red(2), green(3));
        Strategy strategy = new Strategy("rev", List.of(colorChange("entry", "long_entry", 3, Side.BULLISH)));

        Optional<StrategyAlert> first = detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED);
        Optional<StrategyAlert> second = detector.evaluateLatest(instrument, Timeframe.D1, strategy, series, DETECTED);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void empty_series_yields_no_alert() {
        Strategy strategy = new Strategy("rev", List.of(colorChange("entry", "long_entry", 3, Side.BULLISH)));
        assertThat(detector.evaluateLatest(instrument, Timeframe.D1, strategy, List.of(), DETECTED))
                .isEmpty();
    }
}
