package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlert;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link DynamoDbPendingStrategyAlertRepository} backed by
 * LocalStack (CLAUDE.md §2 STRATEGY_PENDING_ALERT, §9 Component 1c SI-3c.3).
 * Validates the live round-trip: enqueue stores the alert JSON (no chart);
 * queryDue returns due items from the distinct RETRY_DUE_STRATEGY partition; the
 * conditional bump is race-safe; delete removes the item.
 */
class DynamoDbPendingStrategyAlertRepositoryIT extends LocalStackITBase {

    private static final Instant NOW = Instant.parse("2026-06-05T00:00:00Z");

    private DynamoDbPendingStrategyAlertRepository repo;

    @BeforeEach
    void setUp() {
        wipeTable();
        repo = new DynamoDbPendingStrategyAlertRepository(CLIENT, TABLE_CONFIG);
    }

    @Test
    void enqueue_then_queryDue_round_trips_the_alert() {
        repo.enqueue(sample(0, NOW));

        List<PendingStrategyAlert> due = repo.queryDue(NOW.plusSeconds(1), 10);
        assertThat(due).hasSize(1);
        PendingStrategyAlert loaded = due.get(0);
        assertThat(loaded.alert().strategyName()).isEqualTo("rsi-reversal-long");
        assertThat(loaded.alert().lines()).hasSize(1);
        assertThat(loaded.alert().lines().get(0).role()).isEqualTo("long_entry");
        assertThat(loaded.retryCount()).isZero();
    }

    @Test
    void queryDue_excludes_future_items() {
        repo.enqueue(sample(0, NOW.plusSeconds(3600)));
        assertThat(repo.queryDue(NOW, 10)).isEmpty();
    }

    @Test
    void enqueue_is_idempotent_on_duplicate_uid() {
        repo.enqueue(sample(0, NOW));
        repo.enqueue(sample(2, NOW));

        Optional<PendingStrategyAlert> loaded = repo.findByUid(uid());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().retryCount()).isZero();
    }

    @Test
    void bumpRetry_is_conditional_on_retry_count() {
        repo.enqueue(sample(0, NOW));

        boolean firstWins = repo.bumpRetry(sample(1, NOW.plusSeconds(3600)), 0);
        boolean staleLoses = repo.bumpRetry(sample(1, NOW.plusSeconds(3600)), 0);

        assertThat(firstWins).isTrue();
        assertThat(staleLoses).isFalse();
        assertThat(repo.findByUid(uid()).orElseThrow().retryCount()).isEqualTo(1);
    }

    @Test
    void delete_removes_the_item() {
        repo.enqueue(sample(0, NOW));
        repo.delete(uid());
        assertThat(repo.findByUid(uid())).isEmpty();
    }

    private static StrategyAlert alert() {
        return new StrategyAlert(
                "abc-123",
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                Instant.parse("2026-05-06T00:00:00Z"),
                "rsi-reversal-long",
                List.of(new StrategyAlertLine(
                        "oversold-entry", "long_entry", Optional.empty(), Optional.empty(), Optional.empty())),
                NOW);
    }

    private static String uid() {
        return PendingStrategyAlert.uidOf(alert());
    }

    private static PendingStrategyAlert sample(int retryCount, Instant retryAt) {
        return new PendingStrategyAlert(
                uid(),
                alert(),
                retryCount,
                retryAt,
                new PendingAlert.LastError("LLM_ERROR", "seeded", NOW, Optional.of("ai")),
                NOW);
    }
}
