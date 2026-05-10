package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link DynamoDbOhlcRepository} backed by LocalStack.
 * Validates the Block 3 / §6 contract: idempotent put with
 * {@code attribute_not_exists(pk)}, scan-index-backward for {@code findLatest},
 * range-bounded {@code findRange}, and atomic SNAPSHOT_ONLY truncate-and-put.
 */
class DynamoDbOhlcRepositoryIT extends LocalStackITBase {

    private static final String INSTRUMENT_ID = "abc-123";
    private DynamoDbOhlcRepository repo;

    @BeforeEach
    void setUp() {
        wipeTable();
        repo = new DynamoDbOhlcRepository(CLIENT, TABLE_CONFIG);
    }

    @Test
    void putBar_idempotent_returns_false_on_duplicate_sk() {
        OHLCBar bar = bar(Instant.parse("2026-05-06T00:00:00Z"));
        assertThat(repo.putBar(bar, Optional.empty())).isTrue();
        assertThat(repo.putBar(bar, Optional.empty())).isFalse();
        assertThat(repo.listAll(INSTRUMENT_ID, Timeframe.D1)).hasSize(1);
    }

    @Test
    void findLatest_returns_the_most_recent_bar() {
        repo.putBar(bar(Instant.parse("2026-05-04T00:00:00Z")), Optional.empty());
        repo.putBar(bar(Instant.parse("2026-05-06T00:00:00Z")), Optional.empty());
        repo.putBar(bar(Instant.parse("2026-05-05T00:00:00Z")), Optional.empty());

        Optional<OHLCBar> latest = repo.findLatest(INSTRUMENT_ID, Timeframe.D1);
        assertThat(latest).isPresent();
        assertThat(latest.get().barTime()).isEqualTo(Instant.parse("2026-05-06T00:00:00Z"));
    }

    @Test
    void findRange_returns_bars_at_or_after_the_cutoff() {
        repo.putBar(bar(Instant.parse("2026-05-04T00:00:00Z")), Optional.empty());
        repo.putBar(bar(Instant.parse("2026-05-05T00:00:00Z")), Optional.empty());
        repo.putBar(bar(Instant.parse("2026-05-06T00:00:00Z")), Optional.empty());

        List<OHLCBar> range = repo.findRange(INSTRUMENT_ID, Timeframe.D1, Instant.parse("2026-05-05T00:00:00Z"));
        assertThat(range).hasSize(2);
        assertThat(range.get(0).barTime()).isEqualTo(Instant.parse("2026-05-05T00:00:00Z"));
        assertThat(range.get(1).barTime()).isEqualTo(Instant.parse("2026-05-06T00:00:00Z"));
    }

    @Test
    void findRange_bounded_returns_only_bars_within_the_closed_interval() {
        repo.putBar(bar(Instant.parse("2026-05-03T00:00:00Z")), Optional.empty());
        repo.putBar(bar(Instant.parse("2026-05-04T00:00:00Z")), Optional.empty());
        repo.putBar(bar(Instant.parse("2026-05-05T00:00:00Z")), Optional.empty());
        repo.putBar(bar(Instant.parse("2026-05-06T00:00:00Z")), Optional.empty());
        repo.putBar(bar(Instant.parse("2026-05-07T00:00:00Z")), Optional.empty());

        List<OHLCBar> range = repo.findRange(
                INSTRUMENT_ID,
                Timeframe.D1,
                Instant.parse("2026-05-04T00:00:00Z"),
                Instant.parse("2026-05-06T00:00:00Z"));
        assertThat(range).hasSize(3);
        assertThat(range.get(0).barTime()).isEqualTo(Instant.parse("2026-05-04T00:00:00Z"));
        assertThat(range.get(2).barTime()).isEqualTo(Instant.parse("2026-05-06T00:00:00Z"));
    }

    @Test
    void snapshotReplace_truncates_existing_bars_and_writes_new_one_atomically() {
        for (int i = 0; i < 5; i++) {
            repo.putBar(bar(Instant.parse("2026-05-0" + (i + 1) + "T00:00:00Z")), Optional.empty());
        }
        assertThat(repo.listAll(INSTRUMENT_ID, Timeframe.D1)).hasSize(5);

        OHLCBar replacement = bar(Instant.parse("2026-05-06T00:00:00Z"));
        repo.snapshotReplace(INSTRUMENT_ID, Timeframe.D1, replacement, Optional.empty());

        List<OHLCBar> after = repo.listAll(INSTRUMENT_ID, Timeframe.D1);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).barTime()).isEqualTo(Instant.parse("2026-05-06T00:00:00Z"));
    }

    @Test
    void listAll_returns_bars_for_a_single_timeframe_only() {
        repo.putBar(bar(Instant.parse("2026-05-06T00:00:00Z")), Optional.empty());
        repo.putBar(weeklyBar(Instant.parse("2026-05-04T00:00:00Z")), Optional.empty());

        assertThat(repo.listAll(INSTRUMENT_ID, Timeframe.D1)).hasSize(1);
        assertThat(repo.listAll(INSTRUMENT_ID, Timeframe.W1)).hasSize(1);
    }

    private static OHLCBar bar(Instant barTime) {
        Instant ingested = Instant.parse("2026-05-07T22:00:00Z");
        return new OHLCBar(
                INSTRUMENT_ID,
                Timeframe.D1,
                barTime,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.of(new BigDecimal("12345")),
                "yahoo",
                ingested);
    }

    private static OHLCBar weeklyBar(Instant barTime) {
        Instant ingested = Instant.parse("2026-05-07T22:00:00Z");
        return new OHLCBar(
                INSTRUMENT_ID,
                Timeframe.W1,
                barTime,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.empty(),
                "yahoo",
                ingested);
    }
}
