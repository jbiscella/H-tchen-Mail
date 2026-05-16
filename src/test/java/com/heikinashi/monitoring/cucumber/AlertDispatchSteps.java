package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.application.CapturingAlertAuditRepository;
import com.heikinashi.monitoring.application.CapturingEmailSender;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.Timeframe;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class AlertDispatchSteps {

    private final World world;

    public AlertDispatchSteps(World world) {
        this.world = world;
    }

    // -------- Given -----------------------------------------------------------

    @Given("the recipients for {string} are {string}")
    public void recipients_for_are(String ticker, String csv) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        Set<String> set = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (set.isEmpty()) {
            // CLAUDE.md §5: empty recipients are valid in default config; force via repo since
            // the service-level updateRecipients rejects empty.
            world.repository()
                    .updateConfig(
                            inst.id(), world.configService().get(inst.id()).withRecipients(Set.of(), world.now()));
            return;
        }
        world.configService().updateRecipients(inst.id(), set);
    }

    @Given("audit logging is enabled")
    public void audit_logging_enabled() {
        world.enableAudit();
        // World wires services with the current auditEnabled flag; rebuild by reconfiguring exchanges.
        world.configureExchanges(Set.of("NASDAQ", "NYSE", "MIL", "XETRA", "LSE", "TSX", "PAR", "AMS"));
    }

    @Given("a staged pattern event for {string} on {string} at {string} with pattern {string}")
    public void stage_pattern_event(String ticker, String tfWire, String iso, String patternSlash) {
        world.stagedEvents().add(buildEvent(ticker, tfWire, iso, patternSlash));
    }

    @Given(
            "a pending alert exists for {string} on {string} at {string} with pattern {string} and retry_at {string} and retry_count {int}")
    public void pending_alert_exists(
            String ticker, String tfWire, String iso, String patternSlash, String retryAtIso, int retryCount) {
        PatternEvent event = buildEvent(ticker, tfWire, iso, patternSlash);
        PendingAlert pending = new PendingAlert(
                PendingAlert.uidOf(event),
                event,
                retryCount,
                Instant.parse(retryAtIso),
                new PendingAlert.LastError(
                        "CHART_RENDER_FAILED", "scripted", Instant.parse(retryAtIso), Optional.of("chart")),
                Instant.parse(retryAtIso));
        world.pendingAlerts().enqueue(pending);
    }

    @Given("the chart renderer will fail the next {int} calls")
    public void chart_renderer_fails_next(int n) {
        world.chartRenderer().failNext(n);
    }

    @Given("the AI analyst will fail the next {int} calls")
    public void ai_analyst_fails_next(int n) {
        world.aiAnalyst().failNext(n);
    }

    @Given("the email sender will reject recipient {string}")
    public void email_sender_will_reject_recipient(String recipient) {
        world.emailSender().rejectRecipient(recipient);
    }

    @Given("the email sender is unavailable")
    public void email_sender_is_unavailable() {
        world.emailSender().makeUnavailable();
    }

    // -------- When ------------------------------------------------------------

    @When("I dispatch the staged events")
    public void i_dispatch_staged_events() {
        world.setLastDispatchSummary(world.alertDispatchService().dispatchAlerts(List.copyOf(world.stagedEvents())));
    }

    @When("I run the retry poller")
    public void i_run_retry_poller() {
        world.setLastPollResult(world.retryPollerService().processBatch());
    }

    // -------- Then ------------------------------------------------------------

    @Then("the dispatch summary has sent={int}, queued={int}, skipped={int}")
    public void dispatch_summary_counts(int sent, int queued, int skipped) {
        assertThat(world.lastDispatchSummary().sent()).as("sent").isEqualTo(sent);
        assertThat(world.lastDispatchSummary().queued()).as("queued").isEqualTo(queued);
        assertThat(world.lastDispatchSummary().skipped()).as("skipped").isEqualTo(skipped);
    }

    @Then("the poll result has processed={int}")
    public void poll_processed(int processed) {
        assertThat(world.lastPollResult().processed()).isEqualTo(processed);
    }

    @Then("the poll result has processed={int}, sent_full={int}, sent_degraded={int}, requeued={int}")
    public void poll_full_counts(int processed, int sentFull, int sentDegraded, int requeued) {
        assertThat(world.lastPollResult().processed()).as("processed").isEqualTo(processed);
        assertThat(world.lastPollResult().sentFull()).as("sentFull").isEqualTo(sentFull);
        assertThat(world.lastPollResult().sentDegraded()).as("sentDegraded").isEqualTo(sentDegraded);
        assertThat(world.lastPollResult().requeued()).as("requeued").isEqualTo(requeued);
    }

    @Then("no alerts are pending")
    public void no_alerts_pending() {
        assertThat(world.pendingAlerts().size()).isZero();
    }

    @Then("{int} alert(s) is/are pending")
    public void n_alerts_pending(int n) {
        assertThat(world.pendingAlerts().size()).isEqualTo(n);
    }

    @Then("{int} alert(s) is/are pending with retry_count {int}")
    public void n_alerts_pending_with_retry_count(int n, int retryCount) {
        assertThat(world.pendingAlerts().size()).isEqualTo(n);
        for (PendingAlert pa : world.pendingAlerts().queryDue(Instant.MAX, 1000)) {
            assertThat(pa.retryCount()).isEqualTo(retryCount);
        }
    }

    @Then("{int} alert is pending with retry_count {int} and last_error code {string}")
    public void alert_pending_with_retry_count_and_last_error(int n, int retryCount, String code) {
        assertThat(world.pendingAlerts().size()).isEqualTo(n);
        PendingAlert pa = world.pendingAlerts().queryDue(Instant.MAX, 1000).get(0);
        assertThat(pa.retryCount()).isEqualTo(retryCount);
        assertThat(pa.lastError().code()).isEqualTo(code);
    }

    @Then("the email sender recorded {int} full send for {int} recipients")
    public void email_recorded_full_for_recipients(int sends, int recipients) {
        long fullCount =
                world.emailSender().sends().stream().filter(s -> !s.degraded()).count();
        assertThat(fullCount).isEqualTo(sends);
        if (sends > 0) {
            CapturingEmailSender.Sent latest =
                    world.emailSender().sends().get(world.emailSender().sends().size() - 1);
            assertThat(latest.recipients()).hasSize(recipients);
        }
    }

    @Then("no email is sent")
    public void no_email_is_sent() {
        assertThat(world.emailSender().sends()).isEmpty();
    }

    @Then("the last send delivered exactly {int} recipient(s)")
    public void last_send_delivered_n(int n) {
        CapturingEmailSender.Sent latest =
                world.emailSender().sends().get(world.emailSender().sends().size() - 1);
        long delivered = latest.deliveries().stream().filter(d -> d.delivered()).count();
        assertThat(delivered).isEqualTo(n);
    }

    @Then("the email sender recorded {int} degraded send with enrichment {string}")
    public void email_recorded_degraded_with_enrichment(int sends, String enrichmentWire) {
        AlertEnrichment expected = AlertEnrichment.valueOf(enrichmentWire.toUpperCase(Locale.ROOT));
        long count = world.emailSender().sends().stream()
                .filter(s -> s.degraded() && s.enrichment() == expected)
                .count();
        assertThat(count).isEqualTo(sends);
    }

    @Then("the audit repository has {int} entry with enrichment {string} and {int} recipients")
    public void audit_repo_has_entry(int n, String enrichmentWire, int recipients) {
        AlertEnrichment expected = AlertEnrichment.valueOf(enrichmentWire.toUpperCase(Locale.ROOT));
        List<CapturingAlertAuditRepository.Audit> matches = world.auditRepo().audits().stream()
                .filter(a -> a.enrichment() == expected)
                .toList();
        assertThat(matches).hasSize(n);
        if (n > 0) {
            assertThat(matches.get(0).recipients()).hasSize(recipients);
        }
    }

    // -------- Helpers ---------------------------------------------------------

    private PatternEvent buildEvent(String ticker, String tfWire, String iso, String patternSlash) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        Timeframe tf = Timeframe.fromWire(tfWire);
        String[] parts = patternSlash.split("/", 2);
        PatternKind kind = PatternKind.fromWire(parts[0]);
        PatternSubtype subtype = PatternSubtype.valueOf(parts[1].toUpperCase(Locale.ROOT));
        BarSnapshot snapshot = new BarSnapshot(
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.empty(),
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"));
        return new PatternEvent(
                inst.id(),
                inst.ticker(),
                inst.exchange(),
                tf,
                Instant.parse(iso),
                kind,
                subtype,
                Map.of(),
                snapshot,
                world.now());
    }
}
