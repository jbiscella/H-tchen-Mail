package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.IngestionSummary;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.OhlcRepository;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.Timeframes;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class QuoteIngestionSteps {

    private final World world;

    public QuoteIngestionSteps(World world) {
        this.world = world;
    }

    // -------- Given -----------------------------------------------------------

    @Given("the provider has no bars for any symbol")
    public void provider_has_no_bars() {
        // default behaviour of the in-memory provider
    }

    @Given("the provider returns no bars for {string}")
    public void provider_returns_no_bars_for(String symbol) {
        world.marketData().primeHistory(symbol, Timeframe.D1, java.util.List.of());
        world.marketData().primeHistory(symbol, Timeframe.W1, java.util.List.of());
    }

    @Given("the provider returns these {string} bars for {string}:")
    public void provider_returns_bars_for(String tfWire, String ticker, DataTable table) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.repository()
                .findById(world.idByAlias(ticker))
                .orElseThrow(() -> new IllegalStateException("unknown alias " + ticker));
        String symbol = symbolFor(inst);
        java.util.List<OHLCBar> bars = new java.util.ArrayList<>();
        for (Map<String, String> row : table.asMaps()) {
            bars.add(new OHLCBar(
                    inst.id(),
                    tf,
                    Instant.parse(row.get("bar_time")),
                    new BigDecimal(row.get("open")),
                    new BigDecimal(row.get("high")),
                    new BigDecimal(row.get("low")),
                    new BigDecimal(row.get("close")),
                    Optional.empty(),
                    "yahoo",
                    world.now()));
        }
        world.marketData().primeHistory(symbol, tf, bars);
    }

    @Given("the provider raises a {string} error for symbol {string}")
    public void provider_raises_error_for_symbol(String error, String symbolOrTicker) {
        // The fake keys errors by the symbol the production code asks for. When
        // the step passes a bare ticker (no dot) we resolve it to the EODHD
        // symbol via the registered Instrument's exchange; full symbols pass
        // through unchanged for direct testing.
        String symbol = resolveSymbol(symbolOrTicker);
        switch (error) {
            case "ticker_not_found" -> world.marketData().primeNotFound(symbol);
            case "provider_unavailable" -> world.marketData().primeProviderUnavailable(symbol);
            case "schema_drift" -> world.marketData().primeSchemaDrift(symbol);
            default -> throw new IllegalArgumentException("unknown error type: " + error);
        }
    }

    private String resolveSymbol(String symbolOrTicker) {
        if (symbolOrTicker.contains(".")) {
            return symbolOrTicker;
        }
        try {
            String id = world.idByAlias(symbolOrTicker);
            return symbolFor(world.repository().findById(id).orElseThrow());
        } catch (IllegalStateException unknownAlias) {
            return symbolOrTicker;
        }
    }

    @Given("a previously stored {string} bar for {string} at {string}")
    public void previously_stored_bar(String tfWire, String ticker, String iso) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        OHLCBar bar = new OHLCBar(
                inst.id(),
                tf,
                Instant.parse(iso),
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.empty(),
                "seed",
                world.now());
        world.ohlcRepository().putBar(bar, Optional.empty());
    }

    @Given("the storage policy for {string} is set to {string}")
    public void storage_policy_set_to(String ticker, String policy) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        world.configService()
                .updateStoragePolicy(
                        inst.id(), com.heikinashi.monitoring.domain.StoragePolicy.fromWire(policy), Optional.empty());
    }

    @Given("the storage policy for {string} is set to {string} with window {int}")
    public void storage_policy_set_with_window(String ticker, String policy, int window) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        world.configService()
                .updateStoragePolicy(
                        inst.id(),
                        com.heikinashi.monitoring.domain.StoragePolicy.fromWire(policy),
                        Optional.of(window));
    }

    @Given("the tracked timeframes for {string} are {string}")
    public void tracked_timeframes_are(String ticker, String csv) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        Set<String> set = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        world.configService().updateTimeframes(inst.id(), set);
    }

    // -------- When ------------------------------------------------------------

    @When("I run ingest_all_active")
    public void i_run_ingest_all_active() {
        world.setLastIngestionSummary(world.ingestionService().ingestAllActive());
    }

    @When("I ingest the active instrument")
    public void i_ingest_the_active_instrument() {
        world.ingestionService().ingestInstrument(world.lastInstrument());
    }

    // -------- Then ------------------------------------------------------------

    @Then("the provider was called for symbols {string}")
    public void provider_called_for_symbols(String csv) {
        Set<String> expected = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::resolveSymbol)
                .collect(Collectors.toSet());
        assertThat(world.marketData().calledSymbols()).containsAll(expected);
    }

    @Then("the provider was not called for symbol {string}")
    public void provider_not_called_for_symbol(String symbol) {
        assertThat(world.marketData().calledSymbols()).doesNotContain(resolveSymbol(symbol));
    }

    @Then("the ingestion summary has processed={int}, succeeded={int}, failed={int}")
    public void ingestion_summary_counts(int processed, int succeeded, int failed) {
        IngestionSummary s = world.lastIngestionSummary();
        assertThat(s.processed()).as("processed").isEqualTo(processed);
        assertThat(s.succeeded()).as("succeeded").isEqualTo(succeeded);
        assertThat(s.failed()).as("failed").isEqualTo(failed);
    }

    @Then("the ingestion summary reports {int} bars inserted")
    public void ingestion_summary_reports_bars_inserted(int n) {
        assertThat(world.lastIngestionSummary().barsInserted()).isEqualTo(n);
    }

    @Then("{int} bar(s) is/are persisted for {string} on {string}")
    public void n_bars_persisted_for(int n, String ticker, String tfWire) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        assertThat(world.ohlcRepository().listAll(inst.id(), Timeframe.fromWire(tfWire)))
                .hasSize(n);
    }

    @Then("exactly {int} bar(s) exists for {string} on {string}")
    public void exactly_n_bars_exists_for(int n, String ticker, String tfWire) {
        n_bars_persisted_for(n, ticker, tfWire);
    }

    @Then("the latest {string} bar persisted for {string} is at {string}")
    public void latest_persisted_at(String tfWire, String ticker, String iso) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        OhlcRepository repo = world.ohlcRepository();
        Optional<OHLCBar> latest = repo.findLatest(inst.id(), Timeframe.fromWire(tfWire));
        assertThat(latest).isPresent();
        assertThat(latest.get().barTime()).isEqualTo(Instant.parse(iso));
    }

    @Then("the bar at {string} for {string} on {string} has no TTL")
    public void bar_has_no_ttl(String iso, String ticker, String tfWire) {
        OHLCBar bar = barAt(iso, ticker, tfWire);
        assertThat(world.ohlcRepository().ttlFor(bar)).isEmpty();
    }

    @Then("the bar at {string} for {string} on {string} has a TTL of bar_time + {int} days")
    public void bar_has_ttl(String iso, String ticker, String tfWire, int days) {
        OHLCBar bar = barAt(iso, ticker, tfWire);
        long expected = bar.barTime().getEpochSecond() + (long) days * Timeframes.ONE_DAY_SECONDS;
        assertThat(world.ohlcRepository().ttlFor(bar)).contains(expected);
    }

    @Then("the instrument {string} is still active")
    public void instrument_still_active(String ticker) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        assertThat(inst.status()).isEqualTo(InstrumentStatus.ACTIVE);
    }

    @And("the operation succeeded")
    public void operation_succeeded() {
        assertThat(world.lastException()).isNull();
    }

    // -------- Helpers ---------------------------------------------------------

    private OHLCBar barAt(String iso, String ticker, String tfWire) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        Instant when = Instant.parse(iso);
        for (OHLCBar b : world.ohlcRepository().listAll(inst.id(), Timeframe.fromWire(tfWire))) {
            if (b.barTime().equals(when)) {
                return b;
            }
        }
        throw new AssertionError("No bar at " + iso + " for " + ticker);
    }

    private String symbolFor(Instrument inst) {
        // mirror IngestionConfig#providerSymbol with the same suffix map the World wires up
        Map<String, String> map = Map.of(
                "NASDAQ", ".US",
                "NYSE", ".US",
                "MIL", ".MI",
                "XETRA", ".XETRA",
                "LSE", ".LSE",
                "TSX", ".TO",
                "PAR", ".PA",
                "AMS", ".AS",
                "SWX", ".SW",
                "BME", ".MC");
        return inst.ticker() + map.getOrDefault(inst.exchange(), "");
    }
}
