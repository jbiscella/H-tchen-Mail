package com.heikinashi.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * PR #80 review (P1) — {@link com.heikinashi.monitoring.domain.OhlcRepository#findLastN}
 * contract: the strategy lookback must read the last N bars <b>by count</b>
 * (inclusive of the boundary, ascending), so market-closure gaps never shrink a
 * long indicator's warmup window — unlike the old calendar-seconds window.
 */
class InMemoryOhlcRepositoryFindLastNTest {

    private static final String ID = "abc-123";

    @Test
    void returns_the_last_n_bars_by_count_inclusive_and_ascending_ignoring_calendar_gaps() {
        InMemoryOhlcRepository repo = new InMemoryOhlcRepository();
        // 10 bars spread over a wide, gappy calendar span (e.g. weekly-ish jumps).
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            Instant t = start.plus(i * 5L, ChronoUnit.DAYS); // 5-day gaps between bars
            repo.putBar(bar(t, i + 1), Optional.empty());
        }
        Instant latest = start.plus(45L, ChronoUnit.DAYS); // the 10th bar (i=9)

        List<OHLCBar> last3 = repo.findLastN(ID, Timeframe.D1, latest, 3);

        assertThat(last3).hasSize(3);
        assertThat(last3).extracting(OHLCBar::barTime).isSorted();
        assertThat(last3.get(2).barTime()).isEqualTo(latest); // inclusive of the boundary bar
        assertThat(last3.get(0).barTime()).isEqualTo(start.plus(35L, ChronoUnit.DAYS));
    }

    @Test
    void caps_at_available_bars_when_n_exceeds_history() {
        InMemoryOhlcRepository repo = new InMemoryOhlcRepository();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        repo.putBar(bar(t0, 1), Optional.empty());
        repo.putBar(bar(t0.plus(1, ChronoUnit.DAYS), 2), Optional.empty());

        assertThat(repo.findLastN(ID, Timeframe.D1, t0.plus(1, ChronoUnit.DAYS), 300))
                .hasSize(2);
    }

    @Test
    void excludes_bars_after_the_inclusive_boundary() {
        InMemoryOhlcRepository repo = new InMemoryOhlcRepository();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        repo.putBar(bar(t0, 1), Optional.empty());
        repo.putBar(bar(t0.plus(1, ChronoUnit.DAYS), 2), Optional.empty());
        repo.putBar(bar(t0.plus(2, ChronoUnit.DAYS), 3), Optional.empty());

        List<OHLCBar> upToMiddle = repo.findLastN(ID, Timeframe.D1, t0.plus(1, ChronoUnit.DAYS), 10);
        assertThat(upToMiddle).hasSize(2);
        assertThat(upToMiddle.get(1).barTime()).isEqualTo(t0.plus(1, ChronoUnit.DAYS));
    }

    private static OHLCBar bar(Instant t, int v) {
        BigDecimal p = BigDecimal.valueOf(100 + v);
        return new OHLCBar(
                ID,
                Timeframe.D1,
                t,
                p,
                p.add(BigDecimal.ONE),
                p.subtract(BigDecimal.ONE),
                p,
                Optional.of(BigDecimal.valueOf(1000)),
                "test",
                t);
    }
}
