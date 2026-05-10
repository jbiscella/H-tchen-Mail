package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PatternEventJsonTest {

    @Test
    void round_trip_preserves_all_fields_including_bar_snapshot() {
        PatternEvent original = sampleEvent(Optional.of(new BigDecimal("123456")));
        String json = PatternEventJson.toJson(original);
        PatternEvent decoded = PatternEventJson.fromJson(json);

        assertThat(decoded.instrumentId()).isEqualTo(original.instrumentId());
        assertThat(decoded.ticker()).isEqualTo(original.ticker());
        assertThat(decoded.exchange()).isEqualTo(original.exchange());
        assertThat(decoded.timeframe()).isEqualTo(original.timeframe());
        assertThat(decoded.barTime()).isEqualTo(original.barTime());
        assertThat(decoded.pattern()).isEqualTo(original.pattern());
        assertThat(decoded.subtype()).isEqualTo(original.subtype());
        assertThat(decoded.detectedAt()).isEqualTo(original.detectedAt());

        BarSnapshot before = original.barSnapshot();
        BarSnapshot after = decoded.barSnapshot();
        assertThat(after.open()).isEqualByComparingTo(before.open());
        assertThat(after.high()).isEqualByComparingTo(before.high());
        assertThat(after.low()).isEqualByComparingTo(before.low());
        assertThat(after.close()).isEqualByComparingTo(before.close());
        assertThat(after.volume()).isPresent();
        assertThat(after.volume().get()).isEqualByComparingTo(before.volume().get());
        assertThat(after.haOpen()).isEqualByComparingTo(before.haOpen());
        assertThat(after.haHigh()).isEqualByComparingTo(before.haHigh());
        assertThat(after.haLow()).isEqualByComparingTo(before.haLow());
        assertThat(after.haClose()).isEqualByComparingTo(before.haClose());
    }

    @Test
    void round_trip_preserves_absent_volume() {
        PatternEvent original = sampleEvent(Optional.empty());
        PatternEvent decoded = PatternEventJson.fromJson(PatternEventJson.toJson(original));
        assertThat(decoded.barSnapshot().volume()).isEmpty();
    }

    @Test
    void params_used_with_BigDecimal_values_round_trips_via_string() {
        Map<String, Object> params = Map.of("wick_tolerance", new BigDecimal("0.001"), "min_streak_length", 3);
        PatternEvent original = sampleEventWithParams(params);
        PatternEvent decoded = PatternEventJson.fromJson(PatternEventJson.toJson(original));
        assertThat(decoded.paramsUsed()).containsEntry("wick_tolerance", "0.001");
        assertThat(decoded.paramsUsed()).containsEntry("min_streak_length", "3");
    }

    private static PatternEvent sampleEvent(Optional<BigDecimal> volume) {
        return new PatternEvent(
                "abc-123",
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                Instant.parse("2026-05-06T00:00:00Z"),
                PatternKind.COLOR_CHANGE,
                PatternSubtype.BULLISH_REVERSAL,
                Map.of("min_streak_length", 3),
                new BarSnapshot(
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("95"),
                        new BigDecimal("105"),
                        volume,
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("95"),
                        new BigDecimal("105")),
                Instant.parse("2026-05-07T22:00:00Z"));
    }

    private static PatternEvent sampleEventWithParams(Map<String, Object> params) {
        PatternEvent base = sampleEvent(Optional.empty());
        return new PatternEvent(
                base.instrumentId(),
                base.ticker(),
                base.exchange(),
                base.timeframe(),
                base.barTime(),
                base.pattern(),
                base.subtype(),
                params,
                base.barSnapshot(),
                base.detectedAt());
    }
}
