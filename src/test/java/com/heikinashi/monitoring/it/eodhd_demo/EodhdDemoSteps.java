package com.heikinashi.monitoring.it.eodhd_demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.TickerNotFoundException;
import com.heikinashi.monitoring.infrastructure.eodhd.EodhdConfig;
import com.heikinashi.monitoring.infrastructure.eodhd.EodhdMarketDataProvider;
import io.cucumber.java.Before;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Assumptions;

/**
 * Cucumber glue for the EODHD demo-key smoke feature. Wires up the real
 * {@link EodhdMarketDataProvider} pointing at {@code https://eodhd.com/api}
 * with {@code api_token=demo}. The hook in {@link #skipIfUnreachable()}
 * pings the endpoint once per scenario and {@link Assumptions#assumeTrue}
 * aborts (skips, not fails) when the network can't reach it.
 */
public final class EodhdDemoSteps {

    private static final String DEMO_KEY = "demo";
    private static final String BASE_URL = "https://eodhd.com/api";
    private static final int TIMEOUT_SECONDS = 10;

    private EodhdMarketDataProvider provider;
    private List<OHLCBar> lastResult;
    private Throwable lastError;

    @Before("@network")
    public void skipIfUnreachable() {
        Assumptions.assumeTrue(eodhdReachable(), "EODHD endpoint not reachable; skipping network smoke test");
        EodhdConfig cfg = new EodhdConfig();
        cfg.setApiKey(DEMO_KEY);
        cfg.setBaseUrl(BASE_URL);
        cfg.setTimeoutSeconds(TIMEOUT_SECONDS);
        provider = new EodhdMarketDataProvider(cfg, Clock.systemUTC());
        lastResult = null;
        lastError = null;
    }

    private static boolean eodhdReachable() {
        // TCP-level reachability isn't enough: some networks let the connect
        // through but the L7 proxy returns 4xx without ever touching EODHD.
        // Probe the public homepage (no quota cost) and require a real 2xx/3xx.
        try {
            HttpClient probe = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<Void> response = probe.send(
                    HttpRequest.newBuilder(URI.create("https://eodhd.com/"))
                            .timeout(Duration.ofSeconds(2))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 400;
        } catch (Exception e) {
            return false;
        }
    }

    @When("I fetch the last {int} days of {string} at timeframe {string}")
    public void i_fetch_last_n_days(int days, String symbol, String tfWire) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        lastResult = provider.fetchHistory(symbol, Timeframe.fromWire(tfWire), since);
    }

    @When("I attempt to fetch the last {int} days of {string} at timeframe {string}")
    public void i_attempt_to_fetch(int days, String symbol, String tfWire) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        lastError = catchThrowable(() -> provider.fetchHistory(symbol, Timeframe.fromWire(tfWire), since));
    }

    @Then("the response contains at least one bar")
    public void at_least_one_bar() {
        assertThat(lastResult).isNotEmpty();
    }

    @Then("the response contains at least {int} bars")
    public void at_least_n_bars(int n) {
        assertThat(lastResult).hasSizeGreaterThanOrEqualTo(n);
    }

    @Then("every bar has open, high, low, close strictly greater than 0")
    public void every_bar_positive() {
        for (OHLCBar b : lastResult) {
            assertThat(b.open()).isGreaterThan(BigDecimal.ZERO);
            assertThat(b.high()).isGreaterThan(BigDecimal.ZERO);
            assertThat(b.low()).isGreaterThan(BigDecimal.ZERO);
            assertThat(b.close()).isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Then("every bar respects the OHLC invariants")
    public void every_bar_respects_invariants() {
        for (OHLCBar b : lastResult) {
            b.validateInvariants();
        }
    }

    @Then("every bar has source {string}")
    public void every_bar_source(String expected) {
        for (OHLCBar b : lastResult) {
            assertThat(b.source()).isEqualTo(expected);
        }
    }

    @Then("the bars are sorted ascending by bar_time")
    public void bars_sorted_ascending() {
        for (int i = 1; i < lastResult.size(); i++) {
            assertThat(lastResult.get(i).barTime())
                    .as("bar %s should be at-or-after bar %s", i, i - 1)
                    .isAfterOrEqualTo(lastResult.get(i - 1).barTime());
        }
    }

    @Then("a TickerNotFoundException is raised")
    public void ticker_not_found_raised() {
        assertThat(lastError).isInstanceOf(TickerNotFoundException.class);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
