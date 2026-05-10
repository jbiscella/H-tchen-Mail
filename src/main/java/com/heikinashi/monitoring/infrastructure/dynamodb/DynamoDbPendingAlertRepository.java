package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingAlertRepository;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * DynamoDB single-table adapter for {@link PendingAlertRepository}.
 *
 * <p>Items live under {@code pk = PENDING_ALERT#<event_uid>}, {@code sk = META},
 * with sparse {@code gsi2Pk = RETRY_DUE} / {@code gsi2Sk = <retry_at_iso>}
 * so {@link #queryDue(Instant, int)} is a single GSI query (CLAUDE.md §2 / §9).
 *
 * <p>Per-method semantics:
 * <ul>
 *   <li>{@link #enqueue(PendingAlert)} — {@code PutItem} with
 *       {@code attribute_not_exists(pk)}; if the guard fails (a prior enqueue
 *       won) the call is silently treated as a no-op so dispatch is idempotent.</li>
 *   <li>{@link #bumpRetry(PendingAlert, int)} — {@code UpdateItem} with
 *       {@code ConditionExpression "retry_count = :expected"}; on
 *       {@link ConditionalCheckFailedException} returns {@code false}
 *       (concurrent poller won the race).</li>
 *   <li>The {@code ttl} attribute is set to {@code created_at + 30 days}
 *       as a safety net, per the spec.</li>
 * </ul>
 */
@Singleton
public class DynamoDbPendingAlertRepository implements PendingAlertRepository {

    private static final long TTL_GRACE_DAYS = 30L;

    private final DynamoDbClient client;
    private final DynamoTableConfig tableConfig;

    public DynamoDbPendingAlertRepository(DynamoDbClient client, DynamoTableConfig tableConfig) {
        this.client = client;
        this.tableConfig = tableConfig;
    }

    @Override
    public void enqueue(PendingAlert pending) {
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .item(buildItem(pending))
                    .conditionExpression("attribute_not_exists(pk)")
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // Already enqueued — preserve original retry_count / retry_at.
        }
    }

    @Override
    public Optional<PendingAlert> findByUid(String eventUid) {
        GetItemResponse resp = client.getItem(GetItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .key(Map.of("pk", s(Keys.pendingAlertPk(eventUid)), "sk", s(Keys.SK_META)))
                .consistentRead(true)
                .build());
        if (!resp.hasItem() || resp.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toPending(resp.item()));
    }

    @Override
    public List<PendingAlert> queryDue(Instant now, int limit) {
        QueryResponse resp = client.query(QueryRequest.builder()
                .tableName(tableConfig.getTableName())
                .indexName(tableConfig.getGsi2Name())
                .keyConditionExpression("gsi2Pk = :pk AND gsi2Sk <= :now")
                .expressionAttributeValues(Map.of(
                        ":pk", s(Keys.GSI2_PK_RETRY_DUE),
                        ":now", s(now.toString())))
                .limit(limit)
                .build());
        List<PendingAlert> out = new ArrayList<>(resp.items().size());
        for (Map<String, AttributeValue> item : resp.items()) {
            out.add(toPending(item));
        }
        return out;
    }

    @Override
    public boolean bumpRetry(PendingAlert updated, int expectedRetryCount) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":expected", n(Integer.toString(expectedRetryCount)));
        values.put(":next", n(Integer.toString(updated.retryCount())));
        values.put(":retryAt", s(updated.retryAt().toString()));
        values.put(":lastError", AttributeValue.fromM(buildLastErrorMap(updated.lastError())));
        values.put(":gsi2Sk", s(updated.retryAt().toString()));
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .key(Map.of(
                            "pk", s(Keys.pendingAlertPk(updated.eventUid())),
                            "sk", s(Keys.SK_META)))
                    .updateExpression(
                            "SET retry_count = :next, retry_at = :retryAt, last_error = :lastError, gsi2Sk = :gsi2Sk")
                    .conditionExpression("retry_count = :expected")
                    .expressionAttributeValues(values)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public void delete(String eventUid) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .key(Map.of("pk", s(Keys.pendingAlertPk(eventUid)), "sk", s(Keys.SK_META)))
                .build());
    }

    private Map<String, AttributeValue> buildItem(PendingAlert pa) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(Keys.pendingAlertPk(pa.eventUid())));
        item.put("sk", s(Keys.SK_META));
        item.put("entity", s(Keys.ENTITY_PENDING_ALERT));
        item.put("event_uid", s(pa.eventUid()));
        item.put("event", s(PatternEventJson.toJson(pa.event())));
        item.put("retry_count", n(Integer.toString(pa.retryCount())));
        item.put("retry_at", s(pa.retryAt().toString()));
        item.put("last_error", AttributeValue.fromM(buildLastErrorMap(pa.lastError())));
        item.put("created_at", s(pa.createdAt().toString()));
        item.put("gsi2Pk", s(Keys.GSI2_PK_RETRY_DUE));
        item.put("gsi2Sk", s(pa.retryAt().toString()));
        long ttl = pa.createdAt().plus(TTL_GRACE_DAYS, ChronoUnit.DAYS).getEpochSecond();
        item.put("ttl", n(Long.toString(ttl)));
        return item;
    }

    private Map<String, AttributeValue> buildLastErrorMap(PendingAlert.LastError err) {
        Map<String, AttributeValue> m = new HashMap<>();
        m.put("code", s(err.code()));
        m.put("message", s(err.message()));
        m.put("ts", s(err.ts().toString()));
        err.componentFailed().ifPresent(c -> m.put("component", s(c)));
        return m;
    }

    private PendingAlert toPending(Map<String, AttributeValue> item) {
        Map<String, AttributeValue> errMap = item.get("last_error").m();
        PendingAlert.LastError err = new PendingAlert.LastError(
                errMap.get("code").s(),
                errMap.get("message").s(),
                Instant.parse(errMap.get("ts").s()),
                Optional.ofNullable(errMap.get("component")).map(AttributeValue::s));
        return new PendingAlert(
                item.get("event_uid").s(),
                PatternEventJson.fromJson(item.get("event").s()),
                Integer.parseInt(item.get("retry_count").n()),
                Instant.parse(item.get("retry_at").s()),
                err,
                Instant.parse(item.get("created_at").s()));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(String v) {
        return AttributeValue.fromN(v);
    }
}
