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

    @When("I run monitoring-main with force_email true")
    public void i_run_monitoring_main_with_force_email() {
        world.setLastMainSummary(
                world.monitoringRunService().execute(MainInput.allActive().withForceEmail(true)));
    }

    @When("I run monitoring-main expecting failure")
    public void i_run_monitoring_main_expecting_failure() {
        world.clearException();
        try {
            world.setLastMainSummary(world.monitoringRunService().execute(MainInput.allActive()));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I run monitoring-main for instruments {string}")
    public void i_run_monitoring_main_for_instruments(String csv) {
        Set<String> ids = parseTickers(csv).stream()
                .map(this::resolveAliasOrPassthrough)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        world.setLastMainSummary(world.monitoringRunService().execute(MainInput.forInstruments(ids)));
    }

    @When("I run monitoring-main for instruments {string} with force_email true")
    public void i_run_monitoring_main_for_instruments_with_force_email(String csv) {
        Set<String> ids = parseTickers(csv).stream()
                .map(this::resolveAliasOrPassthrough)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        world.setLastMainSummary(world.monitoringRunService()
                .execute(MainInput.forInstruments(ids).withForceEmail(true)));
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

    @Then("the main summary reports {int} alerts sent")
    public void main_summary_alerts_sent(int n) {
        assertThat(world.lastMainSummary().alertsSent()).isEqualTo(n);
    }

    @Then("the main summary reports {int} events detected")
    public void main_summary_events_detected(int n) {
        assertThat(world.lastMainSummary().eventsDetected()).isEqualTo(n);
    }

    @Then("the legacy chart renderer is not invoked")
    public void legacy_chart_renderer_not_invoked() {
        assertThat(world.chartRenderer().callCount())
                .as("legacy PatternEvent chart renderer must not run for a strategy instrument")
                .isZero();
    }

    @Then("the strategy chart renderer is not invoked")
    public void strategy_chart_renderer_not_invoked() {
        assertThat(world.strategyChartRenderer().callCount())
                .as("strategy chart must not render when no OHLC bar backs the trigger")
                .isZero();
    }

    @Given("the persisted OHLC read lags behind the just-ingested bar")
    public void persisted_ohlc_read_lags() {
        world.ohlcRepository().simulateReadLag(1);
    }

    @Given("the strategy chart renderer will fail the next {int} calls")
    public void strategy_chart_renderer_fails_next(int n) {
        world.strategyChartRenderer().failNext(n);
    }

    @Given("the strategy pending-alert write will fail")
    public void strategy_pending_alert_write_fails() {
        world.pendingStrategyAlerts().failNextWrite();
    }

    @Then("the run fails with an unhandled error")
    public void run_fails_with_unhandled_error() {
        assertThat(world.lastException())
                .as("the persistence failure must escape the per-instrument guard (legacy parity)")
                .isNotNull();
    }

    @Then("the dispatched strategy alert carries bar time {string}")
    public void dispatched_strategy_alert_carries_bar_time(String barTime) {
        var alert = world.strategyChartRenderer().lastAlert();
        assertThat(alert).as("a strategy alert reached the strategy chart").isNotNull();
        assertThat(alert.barTime()).isEqualTo(java.time.Instant.parse(barTime));
    }

    @Then("the forced strategy alert carries a single honest {string} line")
    public void forced_strategy_alert_single_honest_line(String marker) {
        var alert = world.strategyChartRenderer().lastAlert();
        assertThat(alert)
                .as("a strategy alert was routed to the strategy chart")
                .isNotNull();
        assertThat(alert.lines()).hasSize(1);
        var line = alert.lines().get(0);
        assertThat(line.scenarioName()).isEqualTo(marker);
        assertThat(line.role()).isEqualTo(marker);
        assertThat(line.positionPrecondition()).isEmpty();
        assertThat(line.stopLoss()).isEmpty();
        assertThat(line.takeProfit()).isEmpty();
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
