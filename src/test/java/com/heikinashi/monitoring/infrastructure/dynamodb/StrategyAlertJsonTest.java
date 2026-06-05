package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SI-3c.3 — {@link StrategyAlertJson} round-trip: every matched line (role +
 * verbatim memo) survives serialization into the {@code STRATEGY_PENDING_ALERT}
 * {@code alert} attribute and back, so the retry poller re-sends the same email.
 */
class StrategyAlertJsonTest {

    @Test
    void round_trips_a_multi_line_strategy_alert_with_memos() {
        StrategyAlert original = new StrategyAlert(
                "abc-123",
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                Instant.parse("2026-05-06T00:00:00Z"),
                "rsi-reversal-long",
                List.of(
                        new StrategyAlertLine(
                                "oversold-entry",
                                "long_entry",
                                Optional.of("flat"),
                                Optional.of("entry * 0.98"),
                                Optional.of("entry * 1.05")),
                        new StrategyAlertLine(
                                "overbought-exit", "long_exit", Optional.empty(), Optional.empty(), Optional.empty())),
                Instant.parse("2026-05-06T22:00:00Z"));

        StrategyAlert restored = StrategyAlertJson.fromJson(StrategyAlertJson.toJson(original));

        assertThat(restored).isEqualTo(original);
    }
}
