package com.heikinashi.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AAA unit tests for {@link StrategyRetryPollerService#withTriggerBar} — the
 * V7-safe trigger-bar restore used on retry when SNAPSHOT_ONLY retention evicted
 * the triggering bar before the chart is re-rendered (CLAUDE.md §2 / Component 1c).
 * Not covered by the Cucumber retry scenarios at this granularity (boundary
 * cases: already-present, absent-snapshot, ordering).
 */
class StrategyRetryPollerWithTriggerBarTest {

    private static final Instant T0 = Instant.parse("2026-05-04T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-05T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-06T00:00:00Z"); // the alert bar

    @Test
    void splices_the_trigger_bar_back_in_ascending_order_when_evicted() {
        // Arrange: lookback no longer contains the alert bar at T2.
        List<OHLCBar> bars = List.of(bar(T0), bar(T1));
        OHLCBar trigger = bar(T2);

        // Act
        List<OHLCBar> result = StrategyRetryPollerService.withTriggerBar(bars, T2, Optional.of(trigger));

        // Assert: T2 restored, strictly ascending (heerwisch V3/V4/V7).
        assertThat(result).extracting(OHLCBar::barTime).containsExactly(T0, T1, T2);
    }

    @Test
    void returns_input_unchanged_when_the_bar_is_already_present() {
        List<OHLCBar> bars = List.of(bar(T0), bar(T1), bar(T2));

        List<OHLCBar> result = StrategyRetryPollerService.withTriggerBar(bars, T2, Optional.of(bar(T2)));

        assertThat(result).isSameAs(bars); // no copy, no duplicate
    }

    @Test
    void returns_input_unchanged_when_no_snapshot_was_kept() {
        List<OHLCBar> bars = List.of(bar(T0), bar(T1));

        List<OHLCBar> result = StrategyRetryPollerService.withTriggerBar(bars, T2, Optional.empty());

        assertThat(result).isSameAs(bars);
    }

    private static OHLCBar bar(Instant at) {
        return new OHLCBar(
                "abc-123",
                Timeframe.D1,
                at,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.empty(),
                "test",
                at);
    }
}
