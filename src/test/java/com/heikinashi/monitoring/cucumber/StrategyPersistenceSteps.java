package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import com.heikinashi.monitoring.infrastructure.dynamodb.DynamoDbStrategyRepository;
import com.heikinashi.monitoring.infrastructure.strategy.StrategyJsonImporter;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * SI-3a — exercises the production STRATEGY-item serialization
 * ({@link DynamoDbStrategyRepository#toItem} / {@link DynamoDbStrategyRepository#fromItem})
 * without a live DynamoDB client: a small in-memory map stands in for the table,
 * keyed by instrument id, so the round-trip and last-write-wins overwrite are
 * verified locally. The live {@code GetItem}/{@code PutItem} path is covered by
 * {@code DynamoDbStrategyRepositoryIT} (LocalStack).
 */
public class StrategyPersistenceSteps {

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

    private final StrategyJsonImporter importer = new StrategyJsonImporter();

    /** In-memory stand-in for the table: instrument id -> the STRATEGY item attributes. */
    private final Map<String, Map<String, AttributeValue>> table = new HashMap<>();

    private String lastSavedJson;
    private Optional<Strategy> queried;

    @Given("an imported strategy JSON saved for instrument {string}")
    public void an_imported_strategy_json_saved(String instrumentId) {
        save(instrumentId, STRATEGY_A);
    }

    @Given("a different strategy JSON saved for instrument {string}")
    public void a_different_strategy_json_saved(String instrumentId) {
        save(instrumentId, STRATEGY_B);
    }

    private void save(String instrumentId, String json) {
        Strategy parsed = importer.fromJson(json);
        table.put(instrumentId, DynamoDbStrategyRepository.toItem(instrumentId, json, parsed.name(), NOW));
        lastSavedJson = json;
    }

    @When("the strategy repository is queried for instrument {string}")
    public void the_strategy_repository_is_queried(String instrumentId) {
        Map<String, AttributeValue> item = table.get(instrumentId);
        queried = item == null ? Optional.empty() : Optional.of(DynamoDbStrategyRepository.fromItem(item));
    }

    @When("the strategy repository is queried for an instrument with no saved strategy")
    public void queried_for_instrument_with_no_saved_strategy() {
        the_strategy_repository_is_queried("never-saved");
    }

    @Then("the strategy is returned with every scenario, role and condition intact")
    public void the_strategy_is_returned_intact() {
        assertThat(queried).isPresent();
        assertThat(queried.get()).isEqualTo(importer.fromJson(lastSavedJson));
    }

    @Then("no strategy is returned")
    public void no_strategy_is_returned() {
        assertThat(queried).isEmpty();
    }

    @Then("the strategy returned is the most recently saved one")
    public void the_strategy_returned_is_the_most_recent() {
        assertThat(queried).isPresent();
        Strategy expected = importer.fromJson(lastSavedJson);
        assertThat(queried.get()).isEqualTo(expected);
        assertThat(queried.get().name()).isEqualTo("macd-cross-short");
        StrategyScenario only = queried.get().scenarios().get(0);
        assertThat(only.role()).isEqualTo("short_entry");
        assertThat(only.conditions()).containsExactly("macd_bearish_cross()");
    }
}
