package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.PatternsConfig;
import com.heikinashi.monitoring.domain.StoragePolicy;
import com.heikinashi.monitoring.domain.Timeframe;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class InstrumentConfigSteps {

    private final World world;

    public InstrumentConfigSteps(World world) {
        this.world = world;
    }

    // -------- When ------------------------------------------------------------

    @When("I get the config for the instrument")
    public void i_get_the_config_for_the_instrument() {
        world.clearException();
        try {
            world.setLastConfig(world.configService().get(world.lastInstrument().id()));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I get the config for instrument id {string}")
    public void i_get_the_config_for_instrument_id(String id) {
        world.clearException();
        try {
            world.setLastConfig(world.configService().get(id));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I update the storage policy to {string}")
    public void i_update_the_storage_policy_to(String policy) {
        world.clearException();
        try {
            world.setLastConfig(world.configService()
                    .updateStoragePolicy(world.lastInstrument().id(), parsePolicy(policy), Optional.empty()));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I update the storage policy to {string} with window size {int}")
    public void i_update_the_storage_policy_to_with_window_size(String policy, int windowSize) {
        world.clearException();
        try {
            world.setLastConfig(world.configService()
                    .updateStoragePolicy(world.lastInstrument().id(), parsePolicy(policy), Optional.of(windowSize)));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I update the storage policy to {string} without window size")
    public void i_update_the_storage_policy_to_without_window_size(String policy) {
        world.clearException();
        try {
            world.setLastConfig(world.configService()
                    .updateStoragePolicy(world.lastInstrument().id(), parsePolicy(policy), Optional.empty()));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I update tracked timeframes to {string}")
    public void i_update_tracked_timeframes_to(String csv) {
        world.clearException();
        Set<String> tfs = parseCsv(csv);
        try {
            world.setLastConfig(world.configService()
                    .updateTimeframes(world.lastInstrument().id(), tfs));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I update pattern {string} with:")
    public void i_update_pattern_with(String pattern, DataTable table) {
        world.clearException();
        Map<String, Object> params = new HashMap<>();
        for (List<String> row : table.asLists()) {
            params.put(row.get(0), row.get(1));
        }
        try {
            world.setLastConfig(
                    world.configService().updatePattern(world.lastInstrument().id(), pattern, params));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I update recipients to {string}")
    public void i_update_recipients_to(String csv) {
        world.clearException();
        Set<String> recipients = parseCsv(csv);
        try {
            world.setLastConfig(world.configService()
                    .updateRecipients(world.lastInstrument().id(), recipients));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    // -------- Then ------------------------------------------------------------

    @Then("the config has storage policy {string} and rolling window size {int}")
    public void config_has_policy_and_window(String policy, int window) {
        InstrumentConfig cfg = currentConfig();
        assertThat(cfg.storagePolicy()).isEqualTo(parsePolicy(policy));
        assertThat(cfg.rollingWindowSize()).contains(window);
    }

    @Then("the config has storage policy {string} and no rolling window size")
    public void config_has_policy_and_no_window(String policy) {
        InstrumentConfig cfg = currentConfig();
        assertThat(cfg.storagePolicy()).isEqualTo(parsePolicy(policy));
        assertThat(cfg.rollingWindowSize()).isEmpty();
    }

    @Then("the config has tracked timeframes {string}")
    public void config_has_timeframes(String csv) {
        Set<Timeframe> expected =
                parseCsv(csv).stream().map(Timeframe::fromWire).collect(Collectors.toSet());
        assertThat(currentConfig().trackedTimeframes()).isEqualTo(expected);
    }

    @Then("the config has all patterns disabled")
    public void config_has_all_patterns_disabled() {
        PatternsConfig p = currentConfig().patterns();
        assertThat(p.colorChange().enabled()).isFalse();
        assertThat(p.strongCandle().enabled()).isFalse();
        assertThat(p.doji().enabled()).isFalse();
    }

    @Then("the config has no recipients")
    public void config_has_no_recipients() {
        assertThat(currentConfig().recipients()).isEmpty();
    }

    @Then("the config has chart enabled and AI analysis enabled")
    public void config_has_chart_and_ai_enabled() {
        InstrumentConfig cfg = currentConfig();
        assertThat(cfg.enableChart()).isTrue();
        assertThat(cfg.enableAiAnalysis()).isTrue();
    }

    @Then("the config has color_change enabled with min_streak_length {int}")
    public void config_color_change_enabled_with(int minStreak) {
        PatternsConfig.ColorChange cc = currentConfig().patterns().colorChange();
        assertThat(cc.enabled()).isTrue();
        assertThat(cc.minStreakLength()).isEqualTo(minStreak);
    }

    @Then("the config has color_change disabled with min_streak_length {int}")
    public void config_color_change_disabled_with(int minStreak) {
        PatternsConfig.ColorChange cc = currentConfig().patterns().colorChange();
        assertThat(cc.enabled()).isFalse();
        assertThat(cc.minStreakLength()).isEqualTo(minStreak);
    }

    @Then("the config has recipients {string}")
    public void config_has_recipients(String csv) {
        Set<String> expected = parseCsv(csv);
        assertThat(currentConfig().recipients()).isEqualTo(expected);
    }

    // -------- Helpers ---------------------------------------------------------

    private InstrumentConfig currentConfig() {
        return world.lastConfig() != null
                ? world.lastConfig()
                : world.configService().get(world.lastInstrument().id());
    }

    private static StoragePolicy parsePolicy(String wire) {
        try {
            return StoragePolicy.fromWire(wire);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList()));
    }
}
