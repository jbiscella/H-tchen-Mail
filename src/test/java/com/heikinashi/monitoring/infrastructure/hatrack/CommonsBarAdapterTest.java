package com.heikinashi.monitoring.infrastructure.hatrack;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Block 11 — the domain <-> commons bar adapter is the single typed boundary. */
class CommonsBarAdapterTest {

    private static final String INSTR = "abc-123";

    private static HABar domainHa(Instant t, String open, String high, String low, String close) {
        return new HABar(
                INSTR,
                Timeframe.D1,
                t,
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                Instant.parse("2026-05-07T22:00:00Z"));
    }

    @Test
    void domain_ha_list_converts_to_a_commons_ha_series_preserving_values_and_order() {
        Instant t0 = Instant.parse("2026-05-05T00:00:00Z");
        Instant t1 = Instant.parse("2026-05-06T00:00:00Z");
        Instant t2 = Instant.parse("2026-05-07T00:00:00Z");
        List<HABar> domain = List.of(
                domainHa(t0, "10", "12", "9", "11"),
                domainHa(t1, "11", "13", "10", "12"),
                domainHa(t2, "12", "14", "11", "13"));

        org.hatrack.commons.HASeries series = CommonsBarAdapter.toCommonsHaSeries(domain);

        assertThat(series.bars()).hasSize(3);
        // timestamp ordering preserved
        assertThat(series.bars().stream().map(org.hatrack.commons.HABar::time)).containsExactly(t0, t1, t2);
        // open/high/low/close equal by BigDecimal compareTo
        for (int i = 0; i < domain.size(); i++) {
            HABar d = domain.get(i);
            org.hatrack.commons.HABar c = series.bars().get(i);
            assertThat(c.haOpen()).usingComparator(BigDecimal::compareTo).isEqualTo(d.haOpen());
            assertThat(c.haHigh()).usingComparator(BigDecimal::compareTo).isEqualTo(d.haHigh());
            assertThat(c.haLow()).usingComparator(BigDecimal::compareTo).isEqualTo(d.haLow());
            assertThat(c.haClose()).usingComparator(BigDecimal::compareTo).isEqualTo(d.haClose());
        }
    }

    @Test
    void scale_is_passed_through_explicitly_never_silently_rounded() {
        // A domain bar whose BigDecimal scale (2) differs from a "natural" integer scale.
        Instant t = Instant.parse("2026-05-06T00:00:00Z");
        BigDecimal scaled = new BigDecimal("100.00"); // scale() == 2
        HABar d = new HABar(INSTR, Timeframe.D1, t, scaled, scaled, scaled, scaled, t);

        org.hatrack.commons.HABar c = CommonsBarAdapter.toCommons(d);

        // value equal by compareTo
        assertThat(c.haClose()).usingComparator(BigDecimal::compareTo).isEqualTo(scaled);
        // ...and the scale is preserved exactly: the adapter performed no rounding.
        assertThat(c.haClose().scale()).isEqualTo(2);
        assertThat(c.haClose()).isSameAs(scaled);
    }

    @Test
    void ohlc_round_trips_through_commons_with_supplied_metadata() {
        Instant t = Instant.parse("2026-05-06T00:00:00Z");
        Instant ingestedAt = Instant.parse("2026-05-07T22:00:00Z");
        OHLCBar original = new OHLCBar(
                INSTR,
                Timeframe.D1,
                t,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.of(new BigDecimal("12345")),
                "eodhd",
                ingestedAt);

        org.hatrack.commons.OHLCBar commons = CommonsBarAdapter.toCommons(original);
        assertThat(commons.time()).isEqualTo(t);
        assertThat(commons.volume()).contains(new BigDecimal("12345"));

        OHLCBar back = CommonsBarAdapter.fromCommons(commons, INSTR, Timeframe.D1, "eodhd", ingestedAt);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void timeframe_bridges_via_the_shared_wire_vocabulary() {
        assertThat(CommonsBarAdapter.toCommons(Timeframe.D1).wire()).isEqualTo("1d");
        assertThat(CommonsBarAdapter.toCommons(Timeframe.W1).wire()).isEqualTo("1w");
        assertThat(CommonsBarAdapter.fromCommons(CommonsBarAdapter.toCommons(Timeframe.D1)))
                .isEqualTo(Timeframe.D1);
        assertThat(CommonsBarAdapter.fromCommons(CommonsBarAdapter.toCommons(Timeframe.W1)))
                .isEqualTo(Timeframe.W1);
    }
}
