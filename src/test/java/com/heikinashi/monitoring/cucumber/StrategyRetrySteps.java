package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlert;
import com.heikinashi.monitoring.domain.PollResult;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SI-3c.3 — drives the strategy-alert retry path: enqueue-on-failure in
 * {@link com.heikinashi.monitoring.application.StrategyAlertDispatchService} and
 * recovery in {@link com.heikinashi.monitoring.application.StrategyRetryPollerService}.
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

    private void queue(int retryCount, boolean withChart) {
        StrategyAlert alert = seedAlert();
        Optional<ChartImage> chart = withChart
                ? Optional.of(new ChartImage(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png", 900, 500))
                : Optional.empty();
        PendingStrategyAlert pending = new PendingStrategyAlert(
                PendingStrategyAlert.uidOf(alert),
                alert,
                chart,
                retryCount,
                world.now(),
                new PendingAlert.LastError("LLM_ERROR", "seeded", world.now(), Optional.of("ai")),
                world.now());
        world.pendingStrategyAlerts().enqueue(pending);
    }

    @Given("a strategy pending alert is queued with retry_count {int} and a stored chart due now")
    public void queued_with_stored_chart(int retryCount) {
        queue(retryCount, true);
    }

    @Given("a strategy pending alert is queued with retry_count {int} and no stored chart due now")
    public void queued_without_stored_chart(int retryCount) {
        queue(retryCount, false);
    }

    @When("the strategy retry poller runs")
    public void the_strategy_retry_poller_runs() {
        pollResult = world.strategyRetryPollerService().processBatch();
        world.setLastPollResult(pollResult);
    }

    @Then("a strategy pending alert is enqueued with retry_count {int}")
    public void a_strategy_pending_alert_is_enqueued_with_retry_count(int retryCount) {
        Optional<PendingStrategyAlert> pending =
                world.pendingStrategyAlerts().findByUid(PendingStrategyAlert.uidOf(world.currentStrategyAlert()));
        assertThat(pending).isPresent();
        assertThat(pending.get().retryCount()).isEqualTo(retryCount);
    }

    @Then("the enqueued strategy pending alert carries the rendered chart bytes")
    public void the_enqueued_alert_carries_chart_bytes() {
        Optional<PendingStrategyAlert> pending =
                world.pendingStrategyAlerts().findByUid(PendingStrategyAlert.uidOf(world.currentStrategyAlert()));
        assertThat(pending).isPresent();
        assertThat(pending.get().chart()).isPresent();
        assertThat(pending.get().chart().get().bytes()).isNotEmpty();
    }

    @Then("the strategy dispatch counts queued {int}")
    public void the_strategy_dispatch_counts_queued(int n) {
        assertThat(world.lastDispatchSummary().queued()).isEqualTo(n);
    }

    @Then("the strategy chart is not re-rendered")
    public void the_strategy_chart_is_not_re_rendered() {
        assertThat(world.strategyChartRenderer().callCount()).isZero();
    }

    @Then("a full strategy email is sent to {string}")
    public void a_full_strategy_email_is_sent_to(String recipient) {
        assertThat(world.emailSender().sends())
                .anyMatch(s -> !s.degraded() && s.recipients().contains(recipient));
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
}
