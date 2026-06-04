package com.heikinashi.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Block 16 — the strategy alert note: role labels, verbatim memo, broker notes, one line per scenario. */
class StrategyAlertTextTest {

    private static StrategyAlert alert(List<StrategyAlertLine> lines) {
        return new StrategyAlert(
                "abc-123",
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                Instant.parse("2026-05-07T00:00:00Z"),
                "ma-cross-long",
                lines,
                Instant.parse("2026-05-07T22:00:00Z"));
    }

    @Test
    void quotes_role_and_memo_verbatim_and_marks_them_as_the_users_responsibility() {
        StrategyAlertLine entry = new StrategyAlertLine(
                "golden-cross-entry",
                "long_entry",
                Optional.of("a long position is open"),
                Optional.of("entry * 0.98"),
                Optional.of("entry * 1.05"));

        String text = StrategyAlertText.render(alert(List.of(entry)));

        assertThat(text).contains("golden-cross-entry [long_entry]");
        // Stop-loss / take-profit are quoted exactly as written — never evaluated to a price.
        assertThat(text).contains("entry * 0.98").contains("entry * 1.05");
        assertThat(text).contains("set this at your broker");
        // Position precondition is shown as context, not evaluated.
        assertThat(text).contains("a long position is open").contains("context");
    }

    @Test
    void an_exit_scenario_is_a_first_class_line_just_like_an_entry() {
        StrategyAlertLine exit = new StrategyAlertLine(
                "take-profit-exit", "long_exit", Optional.empty(), Optional.empty(), Optional.empty());
        String text = StrategyAlertText.render(alert(List.of(exit)));
        assertThat(text).contains("take-profit-exit [long_exit]");
    }

    @Test
    void multiple_matched_scenarios_render_one_line_each_in_a_single_note() {
        StrategyAlertLine a =
                new StrategyAlertLine("s1", "long_entry", Optional.empty(), Optional.empty(), Optional.empty());
        StrategyAlertLine b =
                new StrategyAlertLine("s2", "long_exit", Optional.empty(), Optional.empty(), Optional.empty());

        String text = StrategyAlertText.render(alert(List.of(a, b)));

        assertThat(text).contains("s1 [long_entry]").contains("s2 [long_exit]");
        assertThat(text.lines().filter(l -> l.trim().startsWith("- ")).count()).isEqualTo(2);
    }
}
