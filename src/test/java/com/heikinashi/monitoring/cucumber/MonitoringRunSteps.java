package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.MainInput;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class MonitoringRunSteps {

    private final World world;

    public MonitoringRunSteps(World world) {
        this.world = world;
    }

    // -------- Given -----------------------------------------------------------

    @Given("the soft timeout is {int} minutes")
    public void soft_timeout_is(int minutes) {
        world.setMainSoftTimeout(Duration.ofMinutes(minutes));
        // World wires the run service with the current soft timeout; reconfigure to pick it up.
        world.configureExchanges(Set.of("NASDAQ", "NYSE", "MIL", "XETRA", "LSE", "TSX", "PAR", "AMS"));
    }

    // -------- When ------------------------------------------------------------

    @When("I run monitoring-main")
    public void i_run_monitoring_main() {
        world.setLastMainSummary(world.monitoringRunService().execute(MainInput.allActive()));
    }

    @When("I run monitoring-main for instruments {string}")
    public void i_run_monitoring_main_for_instruments(String csv) {
        Set<String> ids = parseTickers(csv).stream()
                .map(this::resolveAliasOrPassthrough)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        world.setLastMainSummary(world.monitoringRunService().execute(MainInput.forInstruments(ids)));
    }

    private String resolveAliasOrPassthrough(String token) {
        try {
            return world.idByAlias(token);
        } catch (IllegalStateException e) {
            return token;
        }
    }

    @When("I run monitoring-main for instruments by ticker {string}")
    public void i_run_monitoring_main_for_instruments_by_ticker(String ticker) {
        // Resolve archived instruments by alias too, since the registry archive flow keeps the alias.
        Optional<Instrument> inst = world.repository().findById(safeIdByAlias(ticker));
        Set<String> ids = inst.map(i -> Set.of(i.id())).orElse(Set.of());
        world.setLastMainSummary(world.monitoringRunService().execute(MainInput.forInstruments(ids)));
    }

    // -------- Then ------------------------------------------------------------

    @Then("the main summary has processed={int} and succeeded={int} and failed={int}")
    public void main_summary_processed_succeeded_failed(int processed, int succeeded, int failed) {
        assertThat(world.lastMainSummary().instrumentsProcessed()).isEqualTo(processed);
        assertThat(world.lastMainSummary().instrumentsSucceeded()).isEqualTo(succeeded);
        assertThat(world.lastMainSummary().instrumentsFailed()).isEqualTo(failed);
    }

    @Then("the main summary has processed={int}")
    public void main_summary_processed(int processed) {
        assertThat(world.lastMainSummary().instrumentsProcessed()).isEqualTo(processed);
    }

    @Then("the main summary reports {int} bars inserted")
    public void main_summary_bars_inserted(int n) {
        assertThat(world.lastMainSummary().barsInserted()).isEqualTo(n);
    }

    @Then("the main summary reports the soft timeout was hit")
    public void main_summary_soft_timeout_hit() {
        assertThat(world.lastMainSummary().softTimeoutHit()).isTrue();
    }

    // -------- Helpers ---------------------------------------------------------

    private Set<String> parseTickers(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String safeIdByAlias(String alias) {
        try {
            return world.idByAlias(alias);
        } catch (IllegalStateException e) {
            return "missing-" + alias;
        }
    }
}
