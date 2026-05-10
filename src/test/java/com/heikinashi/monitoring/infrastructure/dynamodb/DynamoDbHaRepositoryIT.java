package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link DynamoDbHaRepository} backed by LocalStack.
 * Validates the Block 4 / §7 contract: overwrite put (no condition),
 * {@code findLatestBefore} via descending scan with limit 1,
 * {@code findLastNBefore} window for pattern detection,
 * SNAPSHOT_ONLY truncate-and-put, and {@code deleteAll} for bulk recompute.
 */
class DynamoDbHaRepositoryIT extends LocalStackITBase {

    private static final String INSTRUMENT_ID = "abc-123";
    private DynamoDbHaRepository repo;

    @BeforeEach
    void setUp() {
        wipeTable();
        repo = new DynamoDbHaRepository(CLIENT, TABLE_CONFIG);
    }

    @Test
    void putBar_is_an_overwrite_so_repeated_writes_with_same_key_succeed() {
        HABar bar = ha(Instant.parse("2026-05-06T00:00:00Z"), "100", "100");
        repo.putBar(bar, Optional.empty());

        HABar updated = ha(Instant.parse("2026-05-06T00:00:00Z"), "101", "102");
        repo.putBar(updated, Optional.empty());

        List<HABar> all = repo.listAll(INSTRUMENT_ID, Timeframe.D1);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).haOpen()).isEqualByComparingTo(new BigDecimal("101"));
    }

    @Test
    void findLatestBefore_returns_the_most_recent_bar_strictly_before_the_cutoff() {
        repo.putBar(ha(Instant.parse("2026-05-04T00:00:00Z"), "100", "104"), Optional.empty());
        repo.putBar(ha(Instant.parse("2026-05-05T00:00:00Z"), "104", "108"), Optional.empty());
        repo.putBar(ha(Instant.parse("2026-05-06T00:00:00Z"), "108", "112"), Optional.empty());

        Optional<HABar> before =
                repo.findLatestBefore(INSTRUMENT_ID, Timeframe.D1, Instant.parse("2026-05-06T00:00:00Z"));
        assertThat(before).isPresent();
        assertThat(before.get().barTime()).isEqualTo(Instant.parse("2026-05-05T00:00:00Z"));
    }

    @Test
    void findLastNBefore_returns_up_to_n_bars_in_ascending_order() {
        for (int day = 1; day <= 6; day++) {
            repo.putBar(ha(Instant.parse("2026-05-0" + day + "T00:00:00Z"), "100", "101"), Optional.empty());
        }
        List<HABar> last3 = repo.findLastNBefore(INSTRUMENT_ID, Timeframe.D1, Instant.parse("2026-05-06T00:00:00Z"), 3);

        assertThat(last3).hasSize(3);
        assertThat(last3.get(0).barTime()).isEqualTo(Instant.parse("2026-05-03T00:00:00Z"));
        assertThat(last3.get(1).barTime()).isEqualTo(Instant.parse("2026-05-04T00:00:00Z"));
        assertThat(last3.get(2).barTime()).isEqualTo(Instant.parse("2026-05-05T00:00:00Z"));
    }

    @Test
    void snapshotReplace_truncates_and_writes_new_bar_atomically() {
        for (int day = 1; day <= 4; day++) {
            repo.putBar(ha(Instant.parse("2026-05-0" + day + "T00:00:00Z"), "100", "101"), Optional.empty());
        }
        repo.snapshotReplace(
                INSTRUMENT_ID, Timeframe.D1, ha(Instant.parse("2026-05-05T00:00:00Z"), "100", "110"), Optional.empty());

        List<HABar> after = repo.listAll(INSTRUMENT_ID, Timeframe.D1);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).barTime()).isEqualTo(Instant.parse("2026-05-05T00:00:00Z"));
    }

    @Test
    void deleteAll_clears_every_bar_for_the_instrument_and_timeframe() {
        for (int day = 1; day <= 5; day++) {
            repo.putBar(ha(Instant.parse("2026-05-0" + day + "T00:00:00Z"), "100", "101"), Optional.empty());
        }
        assertThat(repo.listAll(INSTRUMENT_ID, Timeframe.D1)).hasSize(5);

        repo.deleteAll(INSTRUMENT_ID, Timeframe.D1);

        assertThat(repo.listAll(INSTRUMENT_ID, Timeframe.D1)).isEmpty();
    }

    private static HABar ha(Instant barTime, String haOpen, String haClose) {
        return new HABar(
                INSTRUMENT_ID,
                Timeframe.D1,
                barTime,
                new BigDecimal(haOpen),
                new BigDecimal(haOpen).max(new BigDecimal(haClose)).add(new BigDecimal("1")),
                new BigDecimal(haOpen).min(new BigDecimal(haClose)).subtract(new BigDecimal("1")),
                new BigDecimal(haClose),
                Instant.parse("2026-05-07T22:00:00Z"));
    }
}
