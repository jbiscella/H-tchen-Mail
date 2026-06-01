package com.heikinashi.monitoring.infrastructure.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.error.StrategyImportException;
import com.heikinashi.monitoring.domain.strategy.MarketCondition;
import com.heikinashi.monitoring.domain.strategy.Side;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import org.junit.jupiter.api.Test;

/** Block 15 — strategy JSON import, including fail-loud on an unsupported condition. */
class StrategyJsonImporterTest {

    private final StrategyJsonImporter importer = new StrategyJsonImporter();

    @Test
    void imports_a_strategy_with_role_memo_and_conditions() {
        String json = """
                {
                  "name": "ma-cross-long",
                  "scenarios": [
                    {
                      "name": "golden-cross-entry",
                      "role": "long_entry",
                      "positionPrecondition": "flat",
                      "stopLoss": "entry * 0.98",
                      "takeProfit": "entry * 1.05",
                      "conditions": [
                        {"type":"moving_average_cross","fastType":"EMA","fastPeriod":50,
                         "slowType":"SMA","slowPeriod":200,"side":"bullish","source":"CLOSE"},
                        {"type":"color_change","minStreakLength":3,"side":"bullish"}
                      ]
                    }
                  ]
                }
                """;

        Strategy strategy = importer.fromJson(json);

        assertThat(strategy.name()).isEqualTo("ma-cross-long");
        assertThat(strategy.scenarios()).hasSize(1);
        StrategyScenario s = strategy.scenarios().get(0);
        assertThat(s.name()).isEqualTo("golden-cross-entry");
        assertThat(s.role()).isEqualTo("long_entry");
        assertThat(s.positionPrecondition()).contains("flat");
        assertThat(s.stopLoss()).contains("entry * 0.98");
        assertThat(s.takeProfit()).contains("entry * 1.05");
        assertThat(s.conditions()).hasSize(2);
        assertThat(s.conditions().get(0)).isInstanceOf(MarketCondition.MovingAverageCross.class);
        MarketCondition.MovingAverageCross mac =
                (MarketCondition.MovingAverageCross) s.conditions().get(0);
        assertThat(mac.fastPeriod()).isEqualTo(50);
        assertThat(mac.slowPeriod()).isEqualTo(200);
        assertThat(mac.side()).isEqualTo(Side.BULLISH);
        assertThat(s.conditions().get(1)).isInstanceOf(MarketCondition.ColorChange.class);
    }

    @Test
    void fails_loud_and_names_an_unsupported_condition() {
        String json = """
                {
                  "name": "exotic",
                  "scenarios": [
                    {"name":"s1","role":"long_entry","conditions":[
                      {"type":"bollinger_squeeze","period":20}
                    ]}
                  ]
                }
                """;
        assertThatThrownBy(() -> importer.fromJson(json))
                .isInstanceOf(StrategyImportException.class)
                .hasMessageContaining("bollinger_squeeze");
    }

    @Test
    void fails_loud_on_a_missing_condition_parameter() {
        String json = """
                {
                  "name": "bad",
                  "scenarios": [
                    {"name":"s1","role":"long_entry","conditions":[
                      {"type":"color_change","side":"bullish"}
                    ]}
                  ]
                }
                """;
        assertThatThrownBy(() -> importer.fromJson(json))
                .isInstanceOf(StrategyImportException.class)
                .hasMessageContaining("minStreakLength");
    }

    @Test
    void fails_loud_on_invalid_json() {
        assertThatThrownBy(() -> importer.fromJson("{not json")).isInstanceOf(StrategyImportException.class);
    }
}
