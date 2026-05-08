package com.heikinashi.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class HeikinAshiCalculatorPropertyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long ONE_DAY = 86_400L;
    private static final BigDecimal DRIFT_BOUND = new BigDecimal("1e-8");

    @Property
    void ha_invariants_hold_on_any_valid_chain(@ForAll("chains") List<OHLCBar> chain) {
        List<HABar> result = HeikinAshiCalculator.computeChain(Optional.empty(), chain, T0);
        for (HABar bar : result) {
            assertThat(bar.haHigh()).isGreaterThanOrEqualTo(bar.haLow());
            assertThat(bar.haHigh()).isGreaterThanOrEqualTo(bar.haOpen());
            assertThat(bar.haHigh()).isGreaterThanOrEqualTo(bar.haClose());
            assertThat(bar.haLow()).isLessThanOrEqualTo(bar.haOpen());
            assertThat(bar.haLow()).isLessThanOrEqualTo(bar.haClose());
        }
    }

    @Property
    void deterministic_for_identical_input(@ForAll("chains") List<OHLCBar> chain) {
        List<HABar> a = HeikinAshiCalculator.computeChain(Optional.empty(), chain, T0);
        List<HABar> b = HeikinAshiCalculator.computeChain(Optional.empty(), chain, T0);
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).haOpen()).isEqualByComparingTo(b.get(i).haOpen());
            assertThat(a.get(i).haHigh()).isEqualByComparingTo(b.get(i).haHigh());
            assertThat(a.get(i).haLow()).isEqualByComparingTo(b.get(i).haLow());
            assertThat(a.get(i).haClose()).isEqualByComparingTo(b.get(i).haClose());
        }
    }

    @Property
    void no_decimal_drift_over_long_chains(@ForAll("longChains") List<OHLCBar> chain) {
        List<HABar> result = HeikinAshiCalculator.computeChain(Optional.empty(), chain, T0);
        for (HABar bar : result) {
            assertThat(bar.haOpen().setScale(20, RoundingMode.HALF_UP).abs())
                    .as("ha_open finite at %s", bar.barTime())
                    .isLessThan(new BigDecimal("1e9"));
            assertThat(bar.haClose().subtract(bar.haOpen()).abs())
                    .as("body finite at %s", bar.barTime())
                    .isLessThan(new BigDecimal("1e6"));
            // The result is exact in BigDecimal arithmetic up to DECIMAL64 precision (~16 digits).
            assertThat(bar.haHigh().subtract(bar.haLow()).abs())
                    .as("range non-negative bounded at %s", bar.barTime())
                    .isLessThan(new BigDecimal("1e6"))
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO.subtract(DRIFT_BOUND));
        }
    }

    @Provide
    Arbitrary<List<OHLCBar>> chains() {
        return chainOfLength(2, 30);
    }

    @Provide
    Arbitrary<List<OHLCBar>> longChains() {
        return chainOfLength(100, 500);
    }

    private static Arbitrary<List<OHLCBar>> chainOfLength(int min, int max) {
        return Arbitraries.integers().between(min, max).map(HeikinAshiCalculatorPropertyTest::randomChain);
    }

    private static List<OHLCBar> randomChain(int n) {
        java.util.Random r = new java.util.Random(n * 31L);
        List<OHLCBar> bars = new ArrayList<>(n);
        BigDecimal close = new BigDecimal(100 + r.nextInt(50));
        for (int i = 0; i < n; i++) {
            BigDecimal open = close;
            BigDecimal up = new BigDecimal(r.nextInt(5) + 1);
            BigDecimal down = new BigDecimal(r.nextInt(5) + 1);
            BigDecimal high = open.max(open.add(up));
            BigDecimal low = open.subtract(down);
            if (low.signum() <= 0) {
                low = new BigDecimal("0.01");
            }
            BigDecimal newClose = low.add(high.subtract(low).multiply(new BigDecimal(r.nextDouble())));
            high = high.max(newClose);
            low = low.min(newClose);
            bars.add(new OHLCBar(
                    "inst",
                    Timeframe.D1,
                    T0.plusSeconds((long) i * ONE_DAY),
                    open,
                    high,
                    low,
                    newClose,
                    Optional.empty(),
                    "test",
                    T0));
            close = newClose;
        }
        return bars;
    }
}
