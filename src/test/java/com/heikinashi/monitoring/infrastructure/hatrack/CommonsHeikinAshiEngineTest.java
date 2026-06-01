package com.heikinashi.monitoring.infrastructure.hatrack;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HeikinAshiCalculator;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Block 12 — HA computation delegates to ha-track commons, and reproduces the
 * canonical reference (and the retained domain calculator) bar-for-bar.
 */
class CommonsHeikinAshiEngineTest {

    private static final String INSTR = "abc-123";
    private static final Instant COMPUTED_AT = Instant.parse("2026-05-07T22:00:00Z");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final CommonsHeikinAshiEngine engine = new CommonsHeikinAshiEngine();

    private static OHLCBar ohlc(int dayIndex, String o, String h, String l, String c) {
        return new OHLCBar(
                INSTR,
                Timeframe.D1,
                T0.plus(dayIndex, ChronoUnit.DAYS),
                new BigDecimal(o),
                new BigDecimal(h),
                new BigDecimal(l),
                new BigDecimal(c),
                Optional.of(new BigDecimal("1000")),
                "eodhd",
                COMPUTED_AT);
    }

    @Test
    void reproduces_the_canonical_first_bar_seed() {
        // CLAUDE.md reference: ha_open[0] = (open+close)/2, ha_close = (o+h+l+c)/4.
        OHLCBar bar = ohlc(0, "100", "110", "95", "105");
        List<HABar> ha = engine.computeChain(Optional.empty(), List.of(bar), COMPUTED_AT);

        assertThat(ha).hasSize(1);
        HABar first = ha.get(0);
        assertThat(first.haOpen())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("102.5")); // (100+105)/2
        assertThat(first.haClose())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("102.5")); // (100+110+95+105)/4
        // metadata re-attached from the source OHLC + caller computedAt
        assertThat(first.instrumentId()).isEqualTo(INSTR);
        assertThat(first.timeframe()).isEqualTo(Timeframe.D1);
        assertThat(first.barTime()).isEqualTo(bar.barTime());
        assertThat(first.computedAt()).isEqualTo(COMPUTED_AT);
    }

    @Test
    void newly_computed_bars_are_returned_for_downstream_detection() {
        List<OHLCBar> chain = List.of(
                ohlc(0, "10", "12", "9", "11"), ohlc(1, "11", "13", "10", "12"), ohlc(2, "12", "14", "11", "13"));
        List<HABar> ha = engine.computeChain(Optional.empty(), chain, COMPUTED_AT);
        assertThat(ha).hasSize(3);
        assertThat(ha.stream().map(HABar::barTime).toList())
                .containsExactly(
                        chain.get(0).barTime(),
                        chain.get(1).barTime(),
                        chain.get(2).barTime());
    }

    @Test
    void recompute_on_unchanged_ohlc_is_idempotent() {
        List<OHLCBar> chain = List.of(ohlc(0, "10", "12", "9", "11"), ohlc(1, "11", "13", "10", "12"));
        List<HABar> first = engine.computeChain(Optional.empty(), chain, COMPUTED_AT);
        List<HABar> second = engine.computeChain(Optional.empty(), chain, COMPUTED_AT);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void empty_input_is_a_no_op() {
        assertThat(engine.computeChain(Optional.empty(), List.of(), COMPUTED_AT))
                .isEmpty();
    }

    /**
     * Cascade guard (CLAUDE.md Block 12): the commons engine must reproduce the
     * retained domain reference calculator bar-for-bar. If commons ever seeds or
     * rounds differently, every persisted HA bar would shift and alert history
     * with it — this test fails loudly before that can merge.
     */
    @Test
    void matches_the_domain_reference_calculator_over_a_long_chain() {
        Random rnd = new Random(42); // fixed seed: fully deterministic
        List<OHLCBar> chain = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double base = 50 + rnd.nextDouble() * 100;
            double hi = base + rnd.nextDouble() * 5;
            double lo = base - rnd.nextDouble() * 5;
            double open = lo + rnd.nextDouble() * (hi - lo);
            double close = lo + rnd.nextDouble() * (hi - lo);
            chain.add(ohlc(
                    i,
                    fmt(open),
                    fmt(Math.max(hi, Math.max(open, close))),
                    fmt(Math.min(lo, Math.min(open, close))),
                    fmt(close)));
        }

        List<HABar> viaCommons = engine.computeChain(Optional.empty(), chain, COMPUTED_AT);
        List<HABar> viaReference = HeikinAshiCalculator.computeChain(Optional.empty(), chain, COMPUTED_AT);

        assertThat(viaCommons).hasSameSizeAs(viaReference);
        for (int i = 0; i < viaCommons.size(); i++) {
            HABar a = viaCommons.get(i);
            HABar b = viaReference.get(i);
            assertThat(a.haOpen()).usingComparator(BigDecimal::compareTo).isEqualTo(b.haOpen());
            assertThat(a.haHigh()).usingComparator(BigDecimal::compareTo).isEqualTo(b.haHigh());
            assertThat(a.haLow()).usingComparator(BigDecimal::compareTo).isEqualTo(b.haLow());
            assertThat(a.haClose()).usingComparator(BigDecimal::compareTo).isEqualTo(b.haClose());
        }
    }

    private static String fmt(double v) {
        return new BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
