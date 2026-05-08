package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PatternDetectionSteps {

    private final World world;
    private List<PatternEvent> lastEvents;
    private List<List<PatternEvent>> recentDetections = new ArrayList<>();

    public PatternDetectionSteps(World world) {
        this.world = world;
    }

    // -------- Given -----------------------------------------------------------

    @Given("the color_change pattern is enabled with min_streak_length {int}")
    public void enable_color_change(int minStreakLength) {
        Map<String, Object> params = Map.of("enabled", "true", "min_streak_length", String.valueOf(minStreakLength));
        world.configService().updatePattern(world.lastInstrument().id(), "color_change", params);
    }

    @Given("the strong_candle pattern is enabled with wick_tolerance {bigdecimal} and min_body_ratio {bigdecimal}")
    public void enable_strong_candle(BigDecimal wickTolerance, BigDecimal minBodyRatio) {
        Map<String, Object> params = Map.of(
                "enabled", "true",
                "wick_tolerance", wickTolerance.toPlainString(),
                "min_body_ratio", minBodyRatio.toPlainString());
        world.configService().updatePattern(world.lastInstrument().id(), "strong_candle", params);
    }

    @Given("the doji pattern is enabled with max_body_ratio {bigdecimal}")
    public void enable_doji(BigDecimal maxBodyRatio) {
        Map<String, Object> params = Map.of("enabled", "true", "max_body_ratio", maxBodyRatio.toPlainString());
        world.configService().updatePattern(world.lastInstrument().id(), "doji", params);
    }

    @Given("the following {string} HA bars are seeded for {string}:")
    public void ha_bars_seeded_for(String tfWire, String ticker, DataTable table) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.repository().findById(world.idByAlias(ticker)).orElseThrow();
        for (Map<String, String> row : table.asMaps()) {
            HABar ha = new HABar(
                    inst.id(),
                    tf,
                    Instant.parse(row.get("bar_time")),
                    new BigDecimal(row.get("ha_open")),
                    new BigDecimal(row.get("ha_high")),
                    new BigDecimal(row.get("ha_low")),
                    new BigDecimal(row.get("ha_close")),
                    world.now());
            world.haRepository().putBar(ha, Optional.empty());

            // Mirror onto OHLC unless an explicit OHLC row is supplied separately —
            // the detector reads OHLC for bar_snapshot purposes.
            if (world.ohlcRepository().listAll(inst.id(), tf).stream()
                    .noneMatch(b -> b.barTime().equals(ha.barTime()))) {
                OHLCBar ohlc = new OHLCBar(
                        inst.id(),
                        tf,
                        ha.barTime(),
                        ha.haOpen(),
                        ha.haHigh(),
                        ha.haLow(),
                        ha.haClose(),
                        Optional.empty(),
                        "test",
                        world.now());
                world.ohlcRepository().putBar(ohlc, Optional.empty());
            }
        }
    }

    // -------- When ------------------------------------------------------------

    @When("I detect patterns on {string} with no new HA bars")
    public void detect_with_no_new_bars(String tfWire) {
        lastEvents = world.patternDetectionService()
                .detectPatterns(world.lastInstrument(), Timeframe.fromWire(tfWire), List.of());
        recentDetections.add(lastEvents);
    }

    @When("I detect patterns on {string} using the bars at {string}")
    public void detect_using_bars_at(String tfWire, String csv) {
        Timeframe tf = Timeframe.fromWire(tfWire);
        Instrument inst = world.lastInstrument();
        List<HABar> all = world.haRepository().listAll(inst.id(), tf);
        List<Instant> wanted = Arrays.stream(csv.split(","))
                .map(String::trim)
                .map(Instant::parse)
                .toList();
        List<HABar> selected = new ArrayList<>();
        for (HABar bar : all) {
            if (wanted.contains(bar.barTime())) {
                selected.add(bar);
            }
        }
        lastEvents = world.patternDetectionService().detectPatterns(inst, tf, selected);
        recentDetections.add(lastEvents);
    }

    // -------- Then ------------------------------------------------------------

    @Then("{int} pattern event(s) is/are emitted")
    public void n_pattern_events_emitted(int n) {
        assertThat(lastEvents).hasSize(n);
    }

    @Then("exactly {int} pattern event(s) is/are emitted")
    public void exactly_n_pattern_events_emitted(int n) {
        n_pattern_events_emitted(n);
    }

    @Then("a pattern event is emitted with pattern {string} and subtype {string}")
    public void pattern_event_with_pattern_and_subtype(String pattern, String subtype) {
        PatternKind k = PatternKind.fromWire(pattern);
        PatternSubtype s = PatternSubtype.valueOf(subtype.toUpperCase(java.util.Locale.ROOT));
        assertThat(lastEvents).anyMatch(e -> e.pattern() == k && e.subtype() == s);
    }

    @Then("the only event has pattern {string} and subtype {string}")
    public void only_event_has(String pattern, String subtype) {
        assertThat(lastEvents).hasSize(1);
        PatternEvent e = lastEvents.get(0);
        assertThat(e.pattern()).isEqualTo(PatternKind.fromWire(pattern));
        assertThat(e.subtype()).isEqualTo(PatternSubtype.valueOf(subtype.toUpperCase(java.util.Locale.ROOT)));
    }

    @Then("no pattern event with pattern {string} is emitted")
    public void no_event_with_pattern(String pattern) {
        PatternKind k = PatternKind.fromWire(pattern);
        assertThat(lastEvents).noneMatch(e -> e.pattern() == k);
    }

    @Then("the event params_used contains {string} with value {int}")
    public void event_params_used_int(String key, int value) {
        assertThat(lastEvents).isNotEmpty();
        Object v = lastEvents.get(0).paramsUsed().get(key);
        assertThat(v).isNotNull();
        assertThat(((Number) v).intValue()).isEqualTo(value);
    }

    @Then("both detections produced the same events")
    public void both_detections_same() {
        assertThat(recentDetections).hasSizeGreaterThanOrEqualTo(2);
        List<PatternEvent> a = recentDetections.get(recentDetections.size() - 2);
        List<PatternEvent> b = recentDetections.get(recentDetections.size() - 1);
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).pattern()).isEqualTo(b.get(i).pattern());
            assertThat(a.get(i).subtype()).isEqualTo(b.get(i).subtype());
            assertThat(a.get(i).barTime()).isEqualTo(b.get(i).barTime());
        }
    }

    @Then("the event has ticker {string} and exchange {string}")
    public void event_has_ticker_and_exchange(String ticker, String exchange) {
        assertThat(lastEvents).isNotEmpty();
        PatternEvent e = lastEvents.get(0);
        assertThat(e.ticker()).isEqualTo(ticker);
        assertThat(e.exchange()).isEqualTo(exchange);
    }

    @Then("the event bar_snapshot has open {bigdecimal}, high {bigdecimal}, low {bigdecimal}, close {bigdecimal}")
    public void event_bar_snapshot_ohlc(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        assertThat(lastEvents).isNotEmpty();
        PatternEvent e = lastEvents.get(0);
        assertThat(e.barSnapshot().open()).isEqualByComparingTo(open);
        assertThat(e.barSnapshot().high()).isEqualByComparingTo(high);
        assertThat(e.barSnapshot().low()).isEqualByComparingTo(low);
        assertThat(e.barSnapshot().close()).isEqualByComparingTo(close);
    }

    @Then(
            "the event bar_snapshot has ha_open {bigdecimal}, ha_high {bigdecimal}, ha_low {bigdecimal}, ha_close {bigdecimal}")
    public void event_bar_snapshot_ha(BigDecimal haOpen, BigDecimal haHigh, BigDecimal haLow, BigDecimal haClose) {
        assertThat(lastEvents).isNotEmpty();
        PatternEvent e = lastEvents.get(0);
        assertThat(e.barSnapshot().haOpen()).isEqualByComparingTo(haOpen);
        assertThat(e.barSnapshot().haHigh()).isEqualByComparingTo(haHigh);
        assertThat(e.barSnapshot().haLow()).isEqualByComparingTo(haLow);
        assertThat(e.barSnapshot().haClose()).isEqualByComparingTo(haClose);
    }
}
