package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.strategy.Strategy;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link DynamoDbStrategyRepository} backed by LocalStack
 * via Testcontainers (CLAUDE.md §2 STRATEGY item, Component 1b SI-3a). Validates
 * the live {@code GetItem}/{@code PutItem} round-trip of the production adapter:
 * a saved strategy reads back intact, an instrument with no STRATEGY item yields
 * empty (legacy detection), and a re-save overwrites the single item.
 */
class DynamoDbStrategyRepositoryIT extends LocalStackITBase {

    private static final Instant NOW = Instant.parse("2026-06-05T00:00:00Z");

    private static final String STRATEGY_A = """
            {
              "name": "rsi-reversal-long",
              "scenarios": [
                {
                  "name": "oversold-bounce-entry",
                  "role": "long_entry",
                  "stopLoss": "entry * 0.98",
                  "conditions": ["rsi(14) crosses below 30", "ha_bullish_reversal(3)"]
                },
                {
                  "name": "overbought-exit",
                  "role": "long_exit",
                  "conditions": ["rsi(14) crosses above 70"]
                }
              ]
            }
            """;

    private static final String STRATEGY_B = """
            {
              "name": "macd-cross-short",
              "scenarios": [
                {
                  "name": "bearish-cross-entry",
                  "role": "short_entry",
                  "conditions": ["macd_bearish_cross()"]
                }
              ]
            }
            """;

    private DynamoDbStrategyRepository repo;

    @BeforeEach
    void setUp() {
        wipeTable();
        repo = new DynamoDbStrategyRepository(CLIENT, TABLE_CONFIG);
    }

    @Test
    void save_then_findByInstrumentId_round_trips_the_strategy() {
        repo.save("abc-123", STRATEGY_A, "rsi-reversal-long", NOW);

        Optional<Strategy> loaded = repo.findByInstrumentId("abc-123");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("rsi-reversal-long");
        assertThat(loaded.get().scenarios()).hasSize(2);
        assertThat(loaded.get().scenarios().get(0).role()).isEqualTo("long_entry");
        assertThat(loaded.get().scenarios().get(0).conditions())
                .containsExactly("rsi(14) crosses below 30", "ha_bullish_reversal(3)");
        assertThat(loaded.get().scenarios().get(0).stopLoss()).contains("entry * 0.98");
        assertThat(loaded.get().scenarios().get(1).role()).isEqualTo("long_exit");
    }

    @Test
    void findByInstrumentId_with_no_strategy_item_returns_empty() {
        assertThat(repo.findByInstrumentId("never-saved")).isEmpty();
    }

    @Test
    void save_overwrites_the_single_strategy_item_last_write_wins() {
        repo.save("abc-123", STRATEGY_A, "rsi-reversal-long", NOW);
        repo.save("abc-123", STRATEGY_B, "macd-cross-short", NOW.plusSeconds(60));

        Optional<Strategy> loaded = repo.findByInstrumentId("abc-123");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("macd-cross-short");
        assertThat(loaded.get().scenarios()).hasSize(1);
        assertThat(loaded.get().scenarios().get(0).role()).isEqualTo("short_entry");
        assertThat(loaded.get().scenarios().get(0).conditions()).containsExactly("macd_bearish_cross()");
    }
}
