package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlertRepository;
import com.heikinashi.monitoring.domain.PollResult;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SI-3c.3 — drives the strategy-alert retry path: enqueue-on-failure in
 * {@link com.heikinashi.monitoring.application.StrategyAlertDispatchService} and
 * recovery in {@link com.heikinashi.monitoring.application.StrategyRetryPollerService},
 * which re-renders the chart from the persisted strategy + bars.
 */
public class StrategyRetrySteps {

    private final World world;
    private PollResult pollResult;

    public StrategyRetrySteps(World world) {
        this.world = world;
    }

    private StrategyAlert seedAlert() {
        if (world.currentStrategyAlert() != null) {
            return world.currentStrategyAlert();
        }
        Instrument inst = world.lastInstrument();
        StrategyAlertLine line = new StrategyAlertLine(
                "oversold-entry", "long_entry", Optional.empty(), Optional.empty(), Optional.empty());
        StrategyAlert alert = new StrategyAlert(
                inst.id(),
                inst.ticker(),
                inst.exchange(),
                Timeframe.D1,
                Instant.parse("2026-05-06T00:00:00Z"),
                "test-strategy",
                List.of(line),
                world.now());
        world.setCurrentStrategyAlert(alert);
        return alert;
    }

    @Given("a strategy is persisted for the instrument")
    public void a_strategy_is_persisted_for_the_instrument() {
        Instrument inst = world.lastInstrument();
        StrategyScenario scenario = new StrategyScenario(
                "oversold-entry",
                "long_entry",
                List.of("rsi(14) crosses below 30"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Strategy strategy = new Strategy("test-strategy", List.of(scenario));
        world.strategyRepository().put(inst.id(), strategy);
        world.setCurrentStrategy(strategy);
        seedAlert();
    }

    @Given("no strategy is persisted for the instrument")
    public void no_strategy_is_persisted_for_the_instrument() {
        seedAlert(); // alert exists, but nothing is put into the strategy repository
    }

    @Given("a strategy pending alert is queued with retry_count {int} due now")
    public void a_strategy_pending_alert_is_queued(int retryCount) {
        enqueuePending(retryCount, Optional.empty());
    }

    @Given("a strategy pending alert is queued with retry_count {int} due now carrying its trigger bar")
    public void a_strategy_pending_alert_is_queued_carrying_trigger_bar(int retryCount) {
        StrategyAlert alert = seedAlert();
        // The OHLC repository deliberately has NO bar at alert.barTime() (retention
        // evicted it); the pending carries the triggering raw bar's snapshot so the
        // retry can synthesize it back into the series before rendering.
        OHLCBar trigger = new OHLCBar(
                alert.instrumentId(),
                alert.timeframe(),
                alert.barTime(),
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.empty(),
                "test",
                alert.detectedAt());
        enqueuePending(retryCount, Optional.of(trigger));
    }

    private void enqueuePending(int retryCount, Optional<OHLCBar> triggerBar) {
        StrategyAlert alert = seedAlert();
        PendingStrategyAlert pending = new PendingStrategyAlert(
                PendingStrategyAlert.uidOf(alert),
                alert,
                triggerBar,
                retryCount,
                world.now(),
                new PendingAlert.LastError("LLM_ERROR", "seeded", world.now(), Optional.of("ai")),
                world.now());
        world.pendingStrategyAlerts().enqueue(pending);
    }

    @Given("the audit write will fail")
    public void the_audit_write_will_fail() {
        world.auditRepo().failNextWrite();
    }

    @When("the strategy retry poller runs")
    public void the_strategy_retry_poller_runs() {
        pollResult = world.strategyRetryPollerService().processBatch();
        world.setLastPollResult(pollResult);
    }

    @When("the strategy retry poller runs twice on the same snapshot")
    public void the_strategy_retry_poller_runs_twice_on_same_snapshot() {
        // Models two concurrent pollers: the due-item view is captured BEFORE the
        // first pass commits, and the second pass replays that stale snapshot
        // (writes still hit the real store) — not merely poll-then-poll-empty.
        List<PendingStrategyAlert> snapshot = world.pendingStrategyAlerts().queryDue(world.now(), 100);
        world.strategyRetryPollerService().processBatch();
        pollResult = world.strategyRetryPollerServiceOver(staleSnapshotOf(world.pendingStrategyAlerts(), snapshot))
                .processBatch();
        world.setLastPollResult(pollResult);
    }

    private static PendingStrategyAlertRepository staleSnapshotOf(
            PendingStrategyAlertRepository real, List<PendingStrategyAlert> snapshot) {
        return new PendingStrategyAlertRepository() {
            @Override
            public void enqueue(PendingStrategyAlert pending) {
                real.enqueue(pending);
            }

            @Override
            public Optional<PendingStrategyAlert> findByUid(String eventUid) {
                return real.findByUid(eventUid);
            }

            @Override
            public List<PendingStrategyAlert> queryDue(Instant now, int limit) {
                return snapshot;
            }

            @Override
            public boolean bumpRetry(PendingStrategyAlert updated, int expectedRetryCount) {
                return real.bumpRetry(updated, expectedRetryCount);
            }

            @Override
            public void delete(String eventUid) {
                real.delete(eventUid);
            }
        };
    }

    @Then("a strategy pending alert is enqueued with retry_count {int}")
    public void a_strategy_pending_alert_is_enqueued_with_retry_count(int retryCount) {
        Optional<PendingStrategyAlert> pending =
                world.pendingStrategyAlerts().findByUid(PendingStrategyAlert.uidOf(world.currentStrategyAlert()));
        assertThat(pending).isPresent();
        assertThat(pending.get().retryCount()).isEqualTo(retryCount);
    }

    @Then("the strategy dispatch counts queued {int}")
    public void the_strategy_dispatch_counts_queued(int n) {
        assertThat(world.lastDispatchSummary().queued()).isEqualTo(n);
    }

    @Then("the strategy chart is re-rendered from the persisted strategy")
    public void the_strategy_chart_is_re_rendered() {
        assertThat(world.strategyChartRenderer().callCount()).isGreaterThanOrEqualTo(1);
    }

    @Then("the re-rendered chart includes the trigger bar")
    public void the_re_rendered_chart_includes_the_trigger_bar() {
        Instant at = world.currentStrategyAlert().barTime();
        assertThat(world.strategyChartRenderer().lastBars())
                .as("synthesized trigger bar must be in the rendered series")
                .anyMatch(b -> b.barTime().equals(at));
    }

    @Then("a full strategy email is sent to {string}")
    public void a_full_strategy_email_is_sent_to(String recipient) {
        assertThat(world.emailSender().sends())
                .anyMatch(s -> !s.degraded() && s.recipients().contains(recipient));
    }

    @Then("exactly {int} full strategy email(s) is/are sent")
    public void exactly_n_full_strategy_emails_are_sent(int n) {
        long full =
                world.emailSender().sends().stream().filter(s -> !s.degraded()).count();
        assertThat(full).as("full strategy sends").isEqualTo(n);
    }

    @Then("a degraded strategy email is sent without a chart")
    public void a_degraded_strategy_email_is_sent_without_a_chart() {
        assertThat(world.emailSender().sends())
                .anyMatch(s -> s.degraded()
                        && (s.enrichment() == AlertEnrichment.DEGRADED_CHART
                                || s.enrichment() == AlertEnrichment.DEGRADED_BOTH));
    }

    @Then("the strategy pending alert is deleted")
    public void the_strategy_pending_alert_is_deleted() {
        assertThat(world.pendingStrategyAlerts().size()).isZero();
    }

    @Then("the strategy pending alert retry_count is {int}")
    public void the_strategy_pending_alert_retry_count_is(int n) {
        Optional<PendingStrategyAlert> pending =
                world.pendingStrategyAlerts().findByUid(PendingStrategyAlert.uidOf(world.currentStrategyAlert()));
        assertThat(pending).isPresent();
        assertThat(pending.get().retryCount()).isEqualTo(n);
    }

    @Then("the strategy pending alert last_error component is {string}")
    public void the_strategy_pending_alert_last_error_component_is(String component) {
        Optional<PendingStrategyAlert> pending =
                world.pendingStrategyAlerts().findByUid(PendingStrategyAlert.uidOf(world.currentStrategyAlert()));
        assertThat(pending).isPresent();
        assertThat(pending.get().lastError().componentFailed()).contains(component);
    }
}
