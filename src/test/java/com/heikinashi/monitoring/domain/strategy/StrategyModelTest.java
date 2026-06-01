package com.heikinashi.monitoring.domain.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.Timeframe;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pure validation of the strategy domain model (Blocks 15-16). */
class StrategyModelTest {

    private static StrategyScenario scenario() {
        return new StrategyScenario(
                "entry",
                "long_entry",
                List.of(new MarketCondition.Doji(new java.math.BigDecimal("0.1"))),
                Optional.of("flat"),
                Optional.of("entry * 0.98"),
                Optional.of("entry * 1.05"));
    }

    @Test
    void scenario_requires_at_least_one_condition() {
        assertThatThrownBy(() ->
                        new StrategyScenario("s", "r", List.of(), Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strategy_requires_at_least_one_scenario() {
        assertThatThrownBy(() -> new Strategy("s", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alert_requires_at_least_one_line() {
        assertThatThrownBy(() -> new StrategyAlert(
                        "id", "AAPL", "NASDAQ", Timeframe.D1, Instant.EPOCH, "strat", List.of(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alert_line_is_derived_from_a_scenario_verbatim() {
        StrategyAlertLine line = StrategyAlertLine.from(scenario());
        assertThat(line.scenarioName()).isEqualTo("entry");
        assertThat(line.role()).isEqualTo("long_entry");
        assertThat(line.positionPrecondition()).contains("flat");
        assertThat(line.stopLoss()).contains("entry * 0.98");
        assertThat(line.takeProfit()).contains("entry * 1.05");
    }

    @Test
    void strategy_round_trips_its_scenarios() {
        Strategy strategy = new Strategy("s", List.of(scenario()));
        assertThat(strategy.scenarios()).hasSize(1);
        assertThat(strategy.scenarios().get(0).conditions()).hasSize(1);
    }
}
