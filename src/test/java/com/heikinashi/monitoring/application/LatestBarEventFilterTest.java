package com.heikinashi.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LatestBarEventFilterTest {

    @Test
    void empty_input_returns_empty() {
        assertThat(LatestBarEventFilter.keepLatestBarOnly(List.of())).isEmpty();
    }

    @Test
    void single_event_passes_through() {
        PatternEvent e = event("2026-05-06T00:00:00Z", PatternKind.COLOR_CHANGE, PatternSubtype.BULLISH_REVERSAL);
        assertThat(LatestBarEventFilter.keepLatestBarOnly(List.of(e))).containsExactly(e);
    }

    @Test
    void keeps_only_events_on_max_bar_time() {
        PatternEvent old1 = event("2026-05-01T00:00:00Z", PatternKind.COLOR_CHANGE, PatternSubtype.BULLISH_REVERSAL);
        PatternEvent old2 = event("2026-05-03T00:00:00Z", PatternKind.COLOR_CHANGE, PatternSubtype.BEARISH_REVERSAL);
        PatternEvent latest = event("2026-05-06T00:00:00Z", PatternKind.DOJI, PatternSubtype.DOJI);
        List<PatternEvent> out = LatestBarEventFilter.keepLatestBarOnly(List.of(old1, old2, latest));
        assertThat(out).containsExactly(latest);
    }

    @Test
    void keeps_multiple_patterns_when_they_share_the_max_bar_time() {
        PatternEvent older = event("2026-05-01T00:00:00Z", PatternKind.COLOR_CHANGE, PatternSubtype.BULLISH_REVERSAL);
        PatternEvent latestColor =
                event("2026-05-06T00:00:00Z", PatternKind.COLOR_CHANGE, PatternSubtype.BULLISH_REVERSAL);
        PatternEvent latestStrong =
                event("2026-05-06T00:00:00Z", PatternKind.STRONG_CANDLE, PatternSubtype.BULLISH_STRONG);
        List<PatternEvent> out = LatestBarEventFilter.keepLatestBarOnly(List.of(older, latestColor, latestStrong));
        assertThat(out).containsExactlyInAnyOrder(latestColor, latestStrong);
    }

    @Test
    void preserves_input_order_within_the_latest_bar() {
        PatternEvent a = event("2026-05-06T00:00:00Z", PatternKind.COLOR_CHANGE, PatternSubtype.BULLISH_REVERSAL);
        PatternEvent b = event("2026-05-06T00:00:00Z", PatternKind.DOJI, PatternSubtype.DOJI);
        List<PatternEvent> out = LatestBarEventFilter.keepLatestBarOnly(List.of(a, b));
        assertThat(out).containsExactly(a, b);
    }

    @Test
    void unordered_input_still_finds_the_max() {
        PatternEvent newest = event("2026-05-06T00:00:00Z", PatternKind.DOJI, PatternSubtype.DOJI);
        PatternEvent middle = event("2026-05-03T00:00:00Z", PatternKind.COLOR_CHANGE, PatternSubtype.BEARISH_REVERSAL);
        PatternEvent oldest = event("2026-05-01T00:00:00Z", PatternKind.COLOR_CHANGE, PatternSubtype.BULLISH_REVERSAL);
        // Pass them in reverse-of-time order — filter must not rely on input ordering.
        List<PatternEvent> out = LatestBarEventFilter.keepLatestBarOnly(List.of(oldest, newest, middle));
        assertThat(out).containsExactly(newest);
    }

    private static PatternEvent event(String barTimeIso, PatternKind kind, PatternSubtype subtype) {
        return new PatternEvent(
                "abc-123",
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                Instant.parse(barTimeIso),
                kind,
                subtype,
                Map.of(),
                new BarSnapshot(
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("95"),
                        new BigDecimal("105"),
                        Optional.empty(),
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("95"),
                        new BigDecimal("105")),
                Instant.parse("2026-05-07T22:00:00Z"));
    }
}
