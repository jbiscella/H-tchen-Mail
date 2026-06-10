package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AAA round-trip for {@link StrategyJson} — the firing-strategy snapshot stored on
 * a STRATEGY_PENDING_ALERT so the retry renders the rules that actually fired
 * (SI-3c.3). Verifies conditions and the optional verbatim memo survive.
 */
class StrategyJsonTest {

    @Test
    void round_trips_scenarios_conditions_and_memo() {
        // Arrange
        Strategy original = new Strategy(
                "rsi-reversal-long",
                List.of(
                        new StrategyScenario(
                                "oversold-entry",
                                "long_entry",
                                List.of("rsi(14) crosses below 30", "ha_bullish_reversal(3)"),
                                Optional.of("no open position"),
                                Optional.of("95.00"),
                                Optional.of("120.00")),
                        new StrategyScenario(
                                "exit",
                                "long_exit",
                                List.of("rsi(14) crosses above 70"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty())));

        // Act
        Strategy restored = StrategyJson.fromJson(StrategyJson.toJson(original));

        // Assert
        assertThat(restored.name()).isEqualTo("rsi-reversal-long");
        assertThat(restored.scenarios()).hasSize(2);
        StrategyScenario entry = restored.scenarios().get(0);
        assertThat(entry.name()).isEqualTo("oversold-entry");
        assertThat(entry.role()).isEqualTo("long_entry");
        assertThat(entry.conditions()).containsExactly("rsi(14) crosses below 30", "ha_bullish_reversal(3)");
        assertThat(entry.positionPrecondition()).contains("no open position");
        assertThat(entry.stopLoss()).contains("95.00");
        assertThat(entry.takeProfit()).contains("120.00");
        StrategyScenario exit = restored.scenarios().get(1);
        assertThat(exit.conditions()).containsExactly("rsi(14) crosses above 70");
        assertThat(exit.stopLoss()).isEmpty();
    }
}
