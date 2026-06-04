package com.heikinashi.monitoring.infrastructure.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.StrategyImportException;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Block 15 — DSL-string strategy import + fail-loud validation against the OHLC series. */
class StrategyJsonImporterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private final StrategyJsonImporter importer = new StrategyJsonImporter();

    /** A wavy, rising OHLC series long enough to warm up RSI/MACD/HA primitives. */
    private static List<OHLCBar> series(int n) {
        List<OHLCBar> bars = new ArrayList<>();
        double prevClose = 100;
        for (int i = 0; i < n; i++) {
            double close = 100 + 10 * Math.sin(i / 4.0) + 0.2 * i;
            BigDecimal c = bd(close);
            bars.add(new OHLCBar(
                    "abc-123",
                    Timeframe.D1,
                    T0.plus(i, ChronoUnit.DAYS),
                    bd(prevClose),
                    c.max(bd(prevClose)).add(BigDecimal.ONE),
                    c.min(bd(prevClose)).subtract(BigDecimal.ONE),
                    c,
                    java.util.Optional.of(bd(1000)),
                    "test",
                    T0));
            prevClose = close;
        }
        return bars;
    }

    private static BigDecimal bd(double v) {
        return new BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    @Test
    void imports_dsl_string_conditions_with_role_and_memo() {
        String json = """
                {
                  "name": "rsi-reversal-long",
                  "scenarios": [
                    {
                      "name": "oversold-bounce-entry",
                      "role": "long_entry",
                      "positionPrecondition": "flat",
                      "stopLoss": "entry * 0.98",
                      "takeProfit": "entry * 1.05",
                      "conditions": ["rsi(14) crosses below 30", "ha_bullish_reversal(3)"]
                    }
                  ]
                }
                """;

        Strategy strategy = importer.fromJson(json);

        assertThat(strategy.name()).isEqualTo("rsi-reversal-long");
        assertThat(strategy.scenarios()).hasSize(1);
        var s = strategy.scenarios().get(0);
        assertThat(s.role()).isEqualTo("long_entry");
        assertThat(s.stopLoss()).contains("entry * 0.98");
        assertThat(s.conditions()).containsExactly("rsi(14) crosses below 30", "ha_bullish_reversal(3)");
    }

    @Test
    void rejects_position_state_references_statically_at_json_load() {
        String json = """
                {"name":"s","scenarios":[{"name":"x","role":"long_exit","conditions":[
                  "close is above entry_price"
                ]}]}
                """;
        assertThatThrownBy(() -> importer.fromJson(json))
                .isInstanceOf(StrategyImportException.class)
                .hasMessageContaining("entry_price");
    }

    @Test
    void rejects_a_non_string_condition() {
        String json = """
                {"name":"s","scenarios":[{"name":"x","role":"long_entry","conditions":[
                  {"type":"color_change"}
                ]}]}
                """;
        assertThatThrownBy(() -> importer.fromJson(json)).isInstanceOf(StrategyImportException.class);
    }

    @Test
    void rejects_invalid_json() {
        assertThatThrownBy(() -> importer.fromJson("{not json")).isInstanceOf(StrategyImportException.class);
    }

    @Test
    void importFor_validates_a_supported_strategy_against_the_ohlc_series() {
        String json = """
                {"name":"s","scenarios":[{"name":"x","role":"long_entry","conditions":[
                  "rsi(14) is above 0", "ha_bullish_reversal(3)"
                ]}]}
                """;
        Strategy strategy = importer.importFor(json, series(60), Timeframe.D1);
        assertThat(strategy.scenarios().get(0).conditions()).hasSize(2);
    }

    @Test
    void importFor_fails_loud_on_an_unsupported_primitive() {
        String json = """
                {"name":"s","scenarios":[{"name":"x","role":"long_entry","conditions":[
                  "frobnicate(5)"
                ]}]}
                """;
        assertThatThrownBy(() -> importer.importFor(json, series(60), Timeframe.D1))
                .isInstanceOf(StrategyImportException.class)
                .hasMessageContaining("frobnicate");
    }

    @Test
    void importFor_fails_loud_on_insufficient_history() {
        String json = """
                {"name":"s","scenarios":[{"name":"x","role":"long_entry","conditions":[
                  "rsi(50) is above 0"
                ]}]}
                """;
        assertThatThrownBy(() -> importer.importFor(json, series(5), Timeframe.D1))
                .isInstanceOf(StrategyImportException.class)
                .hasMessageContaining("insufficient history");
    }

    @Test
    void the_bundled_example_strategy_imports_against_a_real_series() throws IOException {
        String json;
        try (var in = getClass().getResourceAsStream("/strategy/example-strategy.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Strategy strategy = importer.importFor(json, series(60), Timeframe.D1);
        assertThat(strategy.name()).isEqualTo("rsi-reversal-long");
        assertThat(strategy.scenarios()).hasSize(2);
    }
}
