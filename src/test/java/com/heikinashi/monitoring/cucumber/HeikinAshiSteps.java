package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.BulkRecomputeResult;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.Timeframes;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class HeikinAshiSteps {

    private final World world;
    private List<HABar> lastHaChain;
    private BulkRecomputeResult lastBulkRecompute;

    public HeikinAshiSteps(World world) {
        this.world = world;
    }

    // -------- Given -----------------------------------------------------------

    @Given("the following {string} OHLC bars exist for {string}:")
    public void ohlc_bars_exist_for(String tfWire, String ticker, DataTable table) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        for (Map<String, String> row : table.asMaps()) {
            OHLCBar bar = new OHLCBar(
                    inst.id(),
                    tf,
                    Instant.parse(row.get("bar_time")),
                    new BigDecimal(row.get("open")),
                    new BigDecimal(row.get("high")),
                    new BigDecimal(row.get("low")),
                    new BigDecimal(row.get("close")),
                    Optional.empty(),
                    "test",
                    world.now());
            world.ohlcRepository().putBar(bar, Optional.empty());
        }
    }

    @Given(
            "a previously stored {string} HA bar for {string} at {string} with ha_open {bigdecimal} and ha_close {bigdecimal}")
    public void previously_stored_ha_bar(
            String tfWire, String ticker, String iso, BigDecimal haOpen, BigDecimal haClose) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        HABar bar = new HABar(
                inst.id(),
                tf,
                Instant.parse(iso),
                haOpen,
                haOpen.max(haClose),
                haOpen.min(haClose),
                haClose,
                world.now());
        world.haRepository().putBar(bar, Optional.empty());
    }

    @Given("HA has previously been computed on {string} from the full OHLC chain")
    public void given_ha_previously_computed(String tfWire) {
        compute_ha_with_full_chain(tfWire);
    }

    @Given("HA has previously been computed on {string} for {string} from the full OHLC chain")
    public void given_ha_previously_computed_for(String tfWire, String ticker) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        java.util.List<OHLCBar> all = world.ohlcRepository().listAll(inst.id(), tf);
        lastHaChain = world.heikinAshiService().computeFor(inst, tf, all);
    }

    // -------- When ------------------------------------------------------------

    @When("I compute HA for the active instrument on {string} with those OHLC bars")
    public void compute_ha_with_those_ohlc_bars(String tfWire) {
        compute_ha_with_full_chain(tfWire);
    }

    @When("I compute HA for the active instrument on {string} with the full OHLC chain")
    public void when_compute_ha_with_full_chain(String tfWire) {
        compute_ha_with_full_chain(tfWire);
    }

    @When("I compute HA for the active instrument on {string} with the OHLC at {string}")
    public void compute_ha_with_ohlc_at(String tfWire, String iso) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.lastInstrument();
        Instant when = Instant.parse(iso);
        OHLCBar match = world.ohlcRepository().listAll(inst.id(), tf).stream()
                .filter(b -> b.barTime().equals(when))
                .findFirst()
                .orElseThrow();
        lastHaChain = world.heikinAshiService().computeFor(inst, tf, List.of(match));
    }

    @When("I compute HA for the active instrument on {string} with no OHLC bars")
    public void compute_ha_with_no_ohlc_bars(String tfWire) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        lastHaChain = world.heikinAshiService().computeFor(world.lastInstrument(), tf, List.of());
    }

    @When("the OHLC at {string} is overwritten with values {bigdecimal}, {bigdecimal}, {bigdecimal}, {bigdecimal}")
    public void overwrite_ohlc_at(String iso, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        Instrument inst = world.lastInstrument();
        Instant when = Instant.parse(iso);
        OHLCBar prev = world.ohlcRepository().listAll(inst.id(), Timeframe.D1).stream()
                .filter(b -> b.barTime().equals(when))
                .findFirst()
                .orElseThrow();
        OHLCBar replacement = new OHLCBar(
                prev.instrumentId(),
                prev.timeframe(),
                prev.barTime(),
                open,
                high,
                low,
                close,
                prev.volume(),
                prev.source(),
                prev.ingestedAt());
        // overwrite via direct fake mutation: snapshotReplace would wipe everything,
        // so we putBar after manually removing the old one — listAll is safe to mutate-via-snapshotReplace.
        // Simpler approach: the in-memory fake's putBar refuses overwrites, so we use snapshotReplace
        // which clears everything. To keep other bars in place, use a small workaround:
        // re-seed the whole timeframe with the replacement substituted.
        List<OHLCBar> all = new ArrayList<>(world.ohlcRepository().listAll(inst.id(), Timeframe.D1));
        all.removeIf(b -> b.barTime().equals(when));
        all.add(replacement);
        world.ohlcRepository().deleteAllAndReinsert(inst.id(), Timeframe.D1, all);
    }

    @When("I bulk-recompute HA for the active instrument on {string}")
    public void bulk_recompute_ha(String tfWire) {
        lastBulkRecompute = world.heikinAshiService().bulkRecompute(world.lastInstrument(), Timeframe.fromWire(tfWire));
    }

    // -------- Then ------------------------------------------------------------

    @Then("{int} HA bar(s) exist(s) for {string} on {string}")
    public void n_ha_bars_exist(int n, String ticker, String tfWire) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        assertThat(world.haRepository().listAll(inst.id(), Timeframe.fromWire(tfWire)))
                .hasSize(n);
    }

    @Then("exactly {int} HA bar(s) exist(s) for {string} on {string}")
    public void exactly_n_ha_bars_exist(int n, String ticker, String tfWire) {
        n_ha_bars_exist(n, ticker, tfWire);
    }

    @Then("the HA bar at {string} has ha_open {bigdecimal}")
    public void ha_bar_has_ha_open(String iso, BigDecimal expected) {
        HABar bar = haAt(iso);
        assertThat(bar.haOpen()).isEqualByComparingTo(expected);
    }

    @Then("the HA bar at {string} has ha_close {bigdecimal}")
    public void ha_bar_has_ha_close(String iso, BigDecimal expected) {
        HABar bar = haAt(iso);
        assertThat(bar.haClose()).isEqualByComparingTo(expected);
    }

    @Then("the returned HA chain has {int} bars")
    public void returned_chain_has_n_bars(int n) {
        assertThat(lastHaChain).as("returned HA chain").hasSize(n);
    }

    @Then("the HA bar at {string} for {string} on {string} has no TTL")
    public void ha_bar_has_no_ttl(String iso, String ticker, String tfWire) {
        HABar bar = haAt(iso, ticker, tfWire);
        assertThat(world.haRepository().ttlFor(bar)).isEmpty();
    }

    @Then("the HA bar at {string} for {string} on {string} has a TTL of bar_time + {int} days")
    public void ha_bar_has_ttl(String iso, String ticker, String tfWire, int days) {
        HABar bar = haAt(iso, ticker, tfWire);
        long expected = bar.barTime().getEpochSecond() + (long) days * Timeframes.ONE_DAY_SECONDS;
        assertThat(world.haRepository().ttlFor(bar)).contains(expected);
    }

    @Then("for every persisted HA bar, ha_high is at least ha_low")
    public void invariant_ha_high_geq_ha_low() {
        for (HABar bar : world.haRepository().listAll(world.lastInstrument().id(), Timeframe.D1)) {
            assertThat(bar.haHigh()).isGreaterThanOrEqualTo(bar.haLow());
        }
    }

    @Then("for every persisted HA bar, ha_high is at least ha_open and ha_close")
    public void invariant_ha_high_geq_open_and_close() {
        for (HABar bar : world.haRepository().listAll(world.lastInstrument().id(), Timeframe.D1)) {
            assertThat(bar.haHigh()).isGreaterThanOrEqualTo(bar.haOpen());
            assertThat(bar.haHigh()).isGreaterThanOrEqualTo(bar.haClose());
        }
    }

    @Then("for every persisted HA bar, ha_low is at most ha_open and ha_close")
    public void invariant_ha_low_leq_open_and_close() {
        for (HABar bar : world.haRepository().listAll(world.lastInstrument().id(), Timeframe.D1)) {
            assertThat(bar.haLow()).isLessThanOrEqualTo(bar.haOpen());
            assertThat(bar.haLow()).isLessThanOrEqualTo(bar.haClose());
        }
    }

    @Then("the bulk recompute reports {int} OHLC and {int} HA bars")
    public void bulk_recompute_reports(int ohlcCount, int haCount) {
        assertThat(lastBulkRecompute.ohlcCount()).isEqualTo(ohlcCount);
        assertThat(lastBulkRecompute.haCount()).isEqualTo(haCount);
    }

    // -------- Helpers ---------------------------------------------------------

    private void compute_ha_with_full_chain(String tfWire) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.lastInstrument();
        List<OHLCBar> all = world.ohlcRepository().listAll(inst.id(), tf);
        lastHaChain = world.heikinAshiService().computeFor(inst, tf, all);
    }

    private HABar haAt(String iso) {
        return haAt(iso, world.lastInstrument().ticker(), "1d");
    }

    private HABar haAt(String iso, String ticker, String tfWire) {
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        Instant when = Instant.parse(iso);
        for (HABar b : world.haRepository().listAll(inst.id(), Timeframe.fromWire(tfWire))) {
            if (b.barTime().equals(when)) {
                return b;
            }
        }
        throw new AssertionError("No HA bar at " + iso + " for " + ticker);
    }
}
