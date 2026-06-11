package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import com.heikinashi.monitoring.infrastructure.chart.ChartConfig;
import com.heikinashi.monitoring.infrastructure.chart.HeerwischStrategyChartRenderer;
import com.heikinashi.monitoring.infrastructure.chart.StrategyChartIndicators;
import com.heikinashi.monitoring.infrastructure.chart.StrategyChartSpec;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.ChartSpec;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.hatrack.heerwisch.api.spec.IndicatorPlacement;
import org.hatrack.heerwisch.api.spec.Pane;

/**
 * SI-1 / SI-2 — drives the pure strategy-chart logic: SI-1 the
 * {@link StrategyChartIndicators} derivation (conditions in, overlays out); SI-2
 * the {@link StrategyChartSpec} builder (strategy + alert + HA bars in, a
 * heerwisch {@code ChartSpec} out). No DB, no PNG rendering.
 */
public class StrategyChartSteps {

    private static final String INSTR = "abc-123";
    private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");

    private Strategy strategy;
    private List<Indicator> derived;

    // SI-2 state
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private List<HABar> bars;
    private Instant triggerBarTime;
    private StrategyAlert alert;
    private ChartSpec spec;
    private ChartImage renderedImage;

    // -------- SI-1 ------------------------------------------------------------

    @Given("a strategy whose single scenario has conditions:")
    public void a_strategy_whose_single_scenario_has_conditions(DataTable table) {
        List<String> conditions = table.asList();
        strategy = new Strategy("test-strategy", List.of(scenario("the-scenario", conditions)));
    }

    @Given("a strategy whose scenarios have these conditions:")
    public void a_strategy_whose_scenarios_have_these_conditions(DataTable table) {
        // rows: | scenario | condition | — group conditions by scenario, source order preserved.
        Map<String, List<String>> byScenario = new LinkedHashMap<>();
        for (Map<String, String> row : table.asMaps()) {
            byScenario
                    .computeIfAbsent(row.get("scenario"), k -> new ArrayList<>())
                    .add(row.get("condition"));
        }
        List<StrategyScenario> scenarios = new ArrayList<>();
        byScenario.forEach((name, conds) -> scenarios.add(scenario(name, conds)));
        strategy = new Strategy("test-strategy", scenarios);
    }

    @When("I derive the chart indicators")
    public void i_derive_the_chart_indicators() {
        derived = StrategyChartIndicators.derive(strategy);
    }

    @Then("the derived indicators are exactly:")
    public void the_derived_indicators_are_exactly(DataTable table) {
        List<Map<String, String>> rows = table.asMaps();
        assertThat(derived).as("derived indicator count").hasSize(rows.size());
        for (Map<String, String> row : rows) {
            assertThat(derived).as("an indicator matching row %s", row).anyMatch(ind -> matches(ind, row));
        }
    }

    @Then("no chart indicators are derived")
    public void no_chart_indicators_are_derived() {
        assertThat(derived).isEmpty();
    }

    @Then("every derived indicator is placed in the {string} pane")
    public void every_derived_indicator_is_placed_in_the_pane(String pane) {
        assertThat(derived).isNotEmpty();
        assertThat(derived).allMatch(ind -> ind.defaultPane().name().equals(pane));
    }

    @Then("every derived indicator reads the raw {string} price source")
    public void every_derived_indicator_reads_the_raw_price_source(String source) {
        assertThat(derived).isNotEmpty();
        assertThat(derived).allSatisfy(ind -> assertThat(priceSourceOf(ind))
                .as("price source of %s", ind)
                .isEqualTo(source));
    }

    @Then("the {string} overlay reads the raw {string} price source")
    public void the_overlay_reads_the_raw_price_source(String type, String source) {
        assertThat(derived)
                .as("a derived %s overlay", type)
                .filteredOn(ind -> ind.getClass().getSimpleName().equals(type))
                .isNotEmpty()
                .allSatisfy(ind -> assertThat(priceSourceOf(ind))
                        .as("price source of %s", ind)
                        .isEqualTo(source));
    }

    @Then("the chart spec uses the {string} candle style")
    public void the_chart_spec_uses_the_candle_style(String style) {
        assertThat(spec.candleStyle().name()).isEqualTo(style);
    }

    // -------- SI-2 ------------------------------------------------------------

    @Given("an HA lookback window of {int} bars")
    public void an_ha_lookback_window_of_bars(int n) {
        bars = new ArrayList<>(n);
        for (int d = 0; d < n; d++) {
            double base = 95 + 0.25 * d + 3.0 * Math.sin(d / 3.5);
            double open = 95 + 0.25 * (d - 1) + 3.0 * Math.sin((d - 1) / 3.5);
            double high = Math.max(open, base) + 1.2;
            double low = Math.min(open, base) - 1.1;
            HABar bar = new HABar(
                    INSTR,
                    Timeframe.D1,
                    T0.plus(d, ChronoUnit.DAYS),
                    bd(open),
                    bd(high),
                    bd(low),
                    bd(base),
                    Instant.parse("2026-05-07T22:00:00Z"));
            bars.add(bar);
            triggerBarTime = bar.barTime();
        }
    }

    @Given("a strategy alert with a {string} line on the latest bar")
    public void a_strategy_alert_with_a_line_on_the_latest_bar(String role) {
        StrategyAlertLine line =
                new StrategyAlertLine("matched-scenario", role, Optional.empty(), Optional.empty(), Optional.empty());
        alert = new StrategyAlert(
                INSTR,
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                triggerBarTime,
                strategy.name(),
                List.of(line),
                Instant.parse("2026-05-07T22:00:00Z"));
    }

    @When("I build the strategy chart spec")
    public void i_build_the_strategy_chart_spec() {
        spec = StrategyChartSpec.build(strategy, alert, bars, new ChartConfig());
    }

    @When("the strategy alert chart is rendered")
    public void the_strategy_alert_chart_is_rendered() {
        renderedImage = new HeerwischStrategyChartRenderer(new ChartConfig()).render(alert, strategy, bars);
    }

    @Then("a PNG chart image of {int}x{int} is produced")
    public void a_png_chart_image_of_is_produced(int width, int height) {
        assertThat(renderedImage.contentType()).isEqualTo("image/png");
        assertThat(renderedImage.widthPx()).isEqualTo(width);
        assertThat(renderedImage.heightPx()).isEqualTo(height);
        assertThat(renderedImage.bytes()).startsWith(PNG_MAGIC);
    }

    @Then("the spec places {string} with period {int} in a sub-pane")
    public void the_spec_places_with_period_in_a_sub_pane(String type, int period) {
        assertThat(placement(type, period))
                .as("%s(%d) placed in a sub-pane", type, period)
                .isPresent()
                .get()
                .matches(p -> p.pane() != Pane.MAIN, "pane is a sub-pane");
    }

    @Then("the spec places {string} with period {int} in the {string} pane")
    public void the_spec_places_with_period_in_the_pane(String type, int period, String pane) {
        assertThat(placement(type, period))
                .as("%s(%d) placed in the %s pane", type, period, pane)
                .isPresent()
                .get()
                .matches(p -> p.pane().name().equals(pane), "pane == " + pane);
    }

    @Then("the spec has an entry marker on the trigger bar with direction {string} and glyph {string}")
    public void the_spec_has_an_entry_marker(String direction, String glyph) {
        assertThat(spec.annotations())
                .as("an entry marker on the trigger bar")
                .anyMatch(a -> a instanceof Annotation.EntryExitMarkerAuto m
                        && m.time().equals(triggerBarTime)
                        && m.direction().name().equals(direction)
                        && m.glyphStyle().name().equals(glyph));
    }

    @Then("the spec has a neutral bar highlight on the trigger bar and no entry marker")
    public void the_spec_has_a_neutral_bar_highlight_and_no_marker() {
        assertThat(spec.annotations())
                .as("a bar highlight on the trigger bar")
                .anyMatch(
                        a -> a instanceof Annotation.BarHighlight h && h.time().equals(triggerBarTime));
        assertThat(spec.annotations())
                .as("no entry marker")
                .noneMatch(a -> a instanceof Annotation.EntryExitMarkerAuto || a instanceof Annotation.EntryExitMarker);
    }

    // -------- helpers ---------------------------------------------------------

    private Optional<IndicatorPlacement> placement(String type, int period) {
        return spec.indicators().stream()
                .filter(p -> matchesType(p.indicator(), type, period))
                .findFirst();
    }

    private static StrategyScenario scenario(String name, List<String> conditions) {
        return new StrategyScenario(
                name, "long_entry", conditions, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static boolean matches(Indicator ind, Map<String, String> row) {
        String type = row.get("type");
        if ("MACD".equals(type)) {
            return ind instanceof Indicator.MACD macd
                    && Integer.parseInt(row.get("fast")) == macd.fastPeriod()
                    && Integer.parseInt(row.get("slow")) == macd.slowPeriod()
                    && Integer.parseInt(row.get("signal")) == macd.signalPeriod();
        }
        return matchesType(ind, type, Integer.parseInt(row.get("period")));
    }

    /** The {@code PriceSource} name an overlay reads, for the source-faithfulness checks. */
    private static String priceSourceOf(Indicator ind) {
        return switch (ind) {
            case Indicator.RSI rsi -> rsi.priceSource().name();
            case Indicator.SMA sma -> sma.priceSource().name();
            case Indicator.EMA ema -> ema.priceSource().name();
            case Indicator.StdDev sd -> sd.priceSource().name();
            case Indicator.MACD macd -> macd.priceSource().name();
            case Indicator.RollingMax rm -> rm.priceSource().name();
            case Indicator.RollingMin rm -> rm.priceSource().name();
            default -> throw new IllegalArgumentException("indicator carries no price source: " + ind);
        };
    }

    private static boolean matchesType(Indicator ind, String type, int period) {
        return switch (type) {
            case "RSI" -> ind instanceof Indicator.RSI rsi && period == rsi.period();
            case "SMA" -> ind instanceof Indicator.SMA sma && period == sma.period();
            case "EMA" -> ind instanceof Indicator.EMA ema && period == ema.period();
            case "ATR" -> ind instanceof Indicator.ATR atr && period == atr.period();
            case "StdDev" -> ind instanceof Indicator.StdDev sd && period == sd.period();
            case "RollingMax" -> ind instanceof Indicator.RollingMax rm && period == rm.period();
            case "RollingMin" -> ind instanceof Indicator.RollingMin rm && period == rm.period();
            default -> throw new IllegalArgumentException("unknown indicator type in feature: " + type);
        };
    }
}
