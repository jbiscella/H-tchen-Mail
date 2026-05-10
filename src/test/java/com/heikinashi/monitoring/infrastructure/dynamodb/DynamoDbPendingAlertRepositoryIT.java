package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link DynamoDbPendingAlertRepository} backed by
 * LocalStack. Validates the Block 6b / §9 contract: idempotent enqueue
 * (same {@code event_uid} → no overwrite), GSI2 {@code RETRY_DUE} query
 * on ISO-sortable {@code retry_at}, conditional {@code bumpRetry} that
 * detects concurrent poller races, and idempotent {@code delete}.
 */
class DynamoDbPendingAlertRepositoryIT extends LocalStackITBase {

    private DynamoDbPendingAlertRepository repo;

    @BeforeEach
    void setUp() {
        wipeTable();
        repo = new DynamoDbPendingAlertRepository(CLIENT, TABLE_CONFIG);
    }

    @Test
    void enqueue_persists_a_pending_alert_recoverable_by_uid() {
        PendingAlert pending =
                pendingAlert(Instant.parse("2026-05-06T00:00:00Z"), Instant.parse("2026-05-07T23:00:00Z"), 0);
        repo.enqueue(pending);

        Optional<PendingAlert> found = repo.findByUid(pending.eventUid());
        assertThat(found).isPresent();
        assertThat(found.get().retryCount()).isEqualTo(0);
        assertThat(found.get().retryAt()).isEqualTo(Instant.parse("2026-05-07T23:00:00Z"));
    }

    @Test
    void enqueue_is_idempotent_second_call_with_same_uid_is_a_no_op() {
        PendingAlert original =
                pendingAlert(Instant.parse("2026-05-06T00:00:00Z"), Instant.parse("2026-05-07T23:00:00Z"), 0);
        repo.enqueue(original);

        // A "newer" pending with the same bar_time → same uid must not overwrite the original.
        PendingAlert later =
                pendingAlert(Instant.parse("2026-05-06T00:00:00Z"), Instant.parse("2026-05-08T00:00:00Z"), 5);
        repo.enqueue(later);

        Optional<PendingAlert> stored = repo.findByUid(original.eventUid());
        assertThat(stored).isPresent();
        assertThat(stored.get().retryCount()).isEqualTo(0);
        assertThat(stored.get().retryAt()).isEqualTo(Instant.parse("2026-05-07T23:00:00Z"));
    }

    @Test
    void queryDue_returns_only_items_with_retry_at_at_or_before_now_via_GSI2() {
        repo.enqueue(pendingAlert(Instant.parse("2026-05-04T00:00:00Z"), Instant.parse("2026-05-07T20:00:00Z"), 0));
        repo.enqueue(pendingAlert(Instant.parse("2026-05-05T00:00:00Z"), Instant.parse("2026-05-07T22:00:00Z"), 0));
        repo.enqueue(pendingAlert(Instant.parse("2026-05-06T00:00:00Z"), Instant.parse("2026-05-08T00:00:00Z"), 0));

        List<PendingAlert> due = repo.queryDue(Instant.parse("2026-05-07T22:00:00Z"), 100);

        assertThat(due).hasSize(2);
        assertThat(due)
                .extracting(p -> p.event().barTime())
                .containsExactlyInAnyOrder(
                        Instant.parse("2026-05-04T00:00:00Z"), Instant.parse("2026-05-05T00:00:00Z"));
    }

    @Test
    void bumpRetry_succeeds_when_expectedRetryCount_matches_the_current_value() {
        PendingAlert pending =
                pendingAlert(Instant.parse("2026-05-06T00:00:00Z"), Instant.parse("2026-05-07T20:00:00Z"), 0);
        repo.enqueue(pending);

        PendingAlert next = pending.bumped(
                Instant.parse("2026-05-07T23:00:00Z"),
                new PendingAlert.LastError(
                        "LLM_ERROR", "transient", Instant.parse("2026-05-07T22:00:00Z"), Optional.of("ai")));
        boolean accepted = repo.bumpRetry(next, 0);

        assertThat(accepted).isTrue();
        Optional<PendingAlert> updated = repo.findByUid(pending.eventUid());
        assertThat(updated).isPresent();
        assertThat(updated.get().retryCount()).isEqualTo(1);
        assertThat(updated.get().retryAt()).isEqualTo(Instant.parse("2026-05-07T23:00:00Z"));
    }

    @Test
    void bumpRetry_returns_false_when_expectedRetryCount_is_stale() {
        // Simulates a concurrent poller that already won the bump.
        PendingAlert pending =
                pendingAlert(Instant.parse("2026-05-06T00:00:00Z"), Instant.parse("2026-05-07T20:00:00Z"), 1);
        repo.enqueue(pending);

        PendingAlert next = pending.bumped(
                Instant.parse("2026-05-07T23:00:00Z"),
                new PendingAlert.LastError(
                        "LLM_ERROR", "transient", Instant.parse("2026-05-07T22:00:00Z"), Optional.of("ai")));
        // Pretend we still think the count is 0 — the conditional must reject.
        boolean accepted = repo.bumpRetry(next, 0);

        assertThat(accepted).isFalse();
        Optional<PendingAlert> stored = repo.findByUid(pending.eventUid());
        assertThat(stored).isPresent();
        assertThat(stored.get().retryCount()).isEqualTo(1);
        assertThat(stored.get().retryAt()).isEqualTo(Instant.parse("2026-05-07T20:00:00Z"));
    }

    @Test
    void delete_then_findByUid_returns_empty_and_is_idempotent() {
        PendingAlert pending =
                pendingAlert(Instant.parse("2026-05-06T00:00:00Z"), Instant.parse("2026-05-07T22:00:00Z"), 0);
        repo.enqueue(pending);

        repo.delete(pending.eventUid());
        repo.delete(pending.eventUid()); // second call: no error

        assertThat(repo.findByUid(pending.eventUid())).isEmpty();
    }

    private static PendingAlert pendingAlert(Instant barTime, Instant retryAt, int retryCount) {
        Instant now = Instant.parse("2026-05-07T22:00:00Z");
        PatternEvent event = new PatternEvent(
                "abc-123",
                "AAPL",
                "NASDAQ",
                Timeframe.D1,
                barTime,
                PatternKind.COLOR_CHANGE,
                PatternSubtype.BULLISH_REVERSAL,
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
                now);
        return new PendingAlert(
                PendingAlert.uidOf(event),
                event,
                retryCount,
                retryAt,
                new PendingAlert.LastError("CHART_RENDER_FAILED", "scripted", now, Optional.of("chart")),
                now);
    }
}
