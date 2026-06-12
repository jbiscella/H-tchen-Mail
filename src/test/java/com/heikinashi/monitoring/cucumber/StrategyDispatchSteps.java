package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.DispatchSummary;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SI-3c.2 — drives the dedicated StrategyAlertDispatchService (chart → AI → email). */
public class StrategyDispatchSteps {

    private final World world;
    private Strategy strategy;
    private StrategyAlert alert;
    private DispatchSummary summary;

    public StrategyDispatchSteps(World world) {
        this.world = world;
    }

    @Given("a strategy alert for {string} with a {string} scenario {string}")
    public void a_strategy_alert_for_with_scenario(String ticker, String role, String scenarioName) {
        Instrument inst = world.lastInstrument();
        StrategyScenario scenario = new StrategyScenario(
                scenarioName,
                role,
                List.of("rsi(14) crosses below 30"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        strategy = new Strategy("test-strategy", List.of(scenario));
        StrategyAlertLine line = StrategyAlertLine.from(scenario);
        alert = new StrategyAlert(
                inst.id(),
                inst.ticker(),
                inst.exchange(),
                Timeframe.D1,
                Instant.parse("2026-05-06T00:00:00Z"),
                strategy.name(),
                List.of(line),
                world.now());
        world.setCurrentStrategy(strategy);
        world.setCurrentStrategyAlert(alert);
    }

    @When("the strategy alert is dispatched")
    public void the_strategy_alert_is_dispatched() {
        // The chart renderer is faked, so the raw OHLC bars are not exercised here.
        summary = world.strategyAlertDispatchService().dispatch(alert, strategy, List.<OHLCBar>of());
        world.setLastDispatchSummary(summary);
    }

    @Then("the strategy chart is rendered")
    public void the_strategy_chart_is_rendered() {
        assertThat(world.strategyChartRenderer().callCount()).isEqualTo(1);
    }

    @Then("the AI analyst runs for the strategy alert")
    public void the_ai_analyst_runs_for_the_strategy_alert() {
        assertThat(world.aiAnalyst().callCount()).isGreaterThanOrEqualTo(1);
    }

    @Then("a strategy email is sent to {string}")
    public void a_strategy_email_is_sent_to(String recipient) {
        assertThat(world.emailSender().sends())
                .as("a recorded strategy send to %s", recipient)
                .anyMatch(s -> s.recipients().contains(recipient));
    }

    @Then("no strategy email is sent")
    public void no_strategy_email_is_sent() {
        assertThat(world.emailSender().sends()).isEmpty();
    }

    @Then("the strategy dispatch counts sent {int}")
    public void the_strategy_dispatch_counts_sent(int n) {
        assertThat(summary.sent()).isEqualTo(n);
    }

    @Then("the strategy dispatch counts skipped {int}")
    public void the_strategy_dispatch_counts_skipped(int n) {
        assertThat(summary.skipped()).isEqualTo(n);
    }
}
