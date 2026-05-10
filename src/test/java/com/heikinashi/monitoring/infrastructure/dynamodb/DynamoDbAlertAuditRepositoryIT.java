package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.AlertEnrichment;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Integration test for {@link DynamoDbAlertAuditRepository} backed by
 * LocalStack. Validates the Block 6c / §9 contract: append-only PutItem with
 * the canonical
 * {@code ALERT#<bar_time>#<pattern>#<subtype>#<sent_at_ms>} sk shape, so
 * audit records are never overwritten and remain queryable per instrument.
 */
class DynamoDbAlertAuditRepositoryIT extends LocalStackITBase {

    private DynamoDbAlertAuditRepository repo;

    @BeforeEach
    void setUp() {
        wipeTable();
        repo = new DynamoDbAlertAuditRepository(CLIENT, TABLE_CONFIG);
    }

    @Test
    void recordSentAlert_persists_an_alert_item_with_the_canonical_sk() {
        PatternEvent event = sampleEvent();
        Instant sentAt = Instant.parse("2026-05-07T22:30:00Z");
        repo.recordSentAlert(event, AlertEnrichment.FULL, Set.of("alice@example.com"), List.of("ses-msg-1"), sentAt);

        QueryResponse resp = CLIENT.query(QueryRequest.builder()
                .tableName(TABLE_NAME)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(Keys.instrumentPk(event.instrumentId())),
                        ":sk", AttributeValue.fromS(Keys.SK_ALERT_PREFIX)))
                .build());

        assertThat(resp.items()).hasSize(1);
        Map<String, AttributeValue> item = resp.items().get(0);
        assertThat(item.get("entity").s()).isEqualTo(Keys.ENTITY_ALERT);
        assertThat(item.get("ticker").s()).isEqualTo("AAPL");
        assertThat(item.get("enrichment").s()).isEqualTo("full");
        assertThat(item.get("recipients").ss()).containsExactly("alice@example.com");
        assertThat(item.get("ses_message_ids").ss()).containsExactly("ses-msg-1");
        assertThat(item.get("sk").s())
                .isEqualTo(Keys.alertSk(
                        event.barTime().toString(),
                        event.pattern().wire(),
                        event.subtype().wire(),
                        sentAt.toEpochMilli()));
    }

    @Test
    void recordSentAlert_is_append_only_distinct_sent_at_ms_yields_distinct_items() {
        PatternEvent event = sampleEvent();
        repo.recordSentAlert(
                event,
                AlertEnrichment.FULL,
                Set.of("alice@example.com"),
                List.of("ses-1"),
                Instant.parse("2026-05-07T22:30:00.000Z"));
        repo.recordSentAlert(
                event,
                AlertEnrichment.DEGRADED_CHART,
                Set.of("alice@example.com"),
                List.of("ses-2"),
                Instant.parse("2026-05-07T22:30:00.500Z"));

        QueryResponse resp = CLIENT.query(QueryRequest.builder()
                .tableName(TABLE_NAME)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(Keys.instrumentPk(event.instrumentId())),
                        ":sk", AttributeValue.fromS(Keys.SK_ALERT_PREFIX)))
                .build());

        assertThat(resp.items()).hasSize(2);
    }

    @Test
    void recordSentAlert_omits_recipients_set_when_empty_to_satisfy_dynamodb() {
        PatternEvent event = sampleEvent();
        repo.recordSentAlert(
                event, AlertEnrichment.DEGRADED_BOTH, Set.of(), List.of(), Instant.parse("2026-05-07T22:30:00Z"));

        QueryResponse resp = CLIENT.query(QueryRequest.builder()
                .tableName(TABLE_NAME)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(Keys.instrumentPk(event.instrumentId())),
                        ":sk", AttributeValue.fromS(Keys.SK_ALERT_PREFIX)))
                .build());

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0)).doesNotContainKey("recipients");
        assertThat(resp.items().get(0)).doesNotContainKey("ses_message_ids");
    }

    private static PatternEvent sampleEvent() {
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
                        Optional.empty(),
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("95"),
                        new BigDecimal("105")),
                Instant.parse("2026-05-07T22:00:00Z"));
    }
}
