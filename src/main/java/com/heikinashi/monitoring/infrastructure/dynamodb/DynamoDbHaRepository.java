package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.Timeframe;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * DynamoDB single-table adapter for {@link HaRepository}. Items live under
 * {@code pk = INSTRUMENT#<id>}, {@code sk = HA#<tf>#<bar_time_iso>}.
 *
 * <p>Per CLAUDE.md §7 idempotency is by overwrite (no condition); same OHLC
 * produces same HA so re-writing is a no-op functionally.
 */
@Singleton
public class DynamoDbHaRepository implements HaRepository {

    private static final int TXN_MAX = 25;
    private static final int BATCH_DELETE_CHUNK = 25;

    private final DynamoDbClient client;
    private final DynamoTableConfig tableConfig;

    public DynamoDbHaRepository(DynamoDbClient client, DynamoTableConfig tableConfig) {
        this.client = client;
        this.tableConfig = tableConfig;
    }

    @Override
    public List<HABar> findLastNBefore(String instrumentId, Timeframe tf, Instant before, int n) {
        if (n <= 0) {
            return List.of();
        }
        QueryResponse resp = client.query(QueryRequest.builder()
                .tableName(tableConfig.getTableName())
                .keyConditionExpression("pk = :pk AND sk BETWEEN :skLow AND :skHigh")
                .expressionAttributeValues(Map.of(
                        ":pk", s(Keys.instrumentPk(instrumentId)),
                        ":skLow", s("HA#" + tf.wire() + "#"),
                        ":skHigh", s("HA#" + tf.wire() + "#" + exclusiveUpperIso(before))))
                .scanIndexForward(false)
                .limit(n)
                .build());
        List<HABar> out = new ArrayList<>(resp.items().size());
        for (Map<String, AttributeValue> item : resp.items()) {
            out.add(toBar(item));
        }
        java.util.Collections.reverse(out);
        return out;
    }

    @Override
    public Optional<HABar> findLatestBefore(String instrumentId, Timeframe tf, Instant before) {
        QueryResponse resp = client.query(QueryRequest.builder()
                .tableName(tableConfig.getTableName())
                .keyConditionExpression("pk = :pk AND sk BETWEEN :skLow AND :skHigh")
                .expressionAttributeValues(Map.of(
                        ":pk", s(Keys.instrumentPk(instrumentId)),
                        ":skLow", s("HA#" + tf.wire() + "#"),
                        ":skHigh", s("HA#" + tf.wire() + "#" + exclusiveUpperIso(before))))
                .scanIndexForward(false)
                .limit(1)
                .build());
        if (resp.items().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toBar(resp.items().get(0)));
    }

    /**
     * DynamoDB's {@code BETWEEN} is inclusive on both bounds, but the
     * {@code findLatestBefore} / {@code findLastNBefore} contract is strict
     * less-than (mirroring {@link java.util.TreeMap#lowerEntry}). Encode the
     * exclusive upper bound by subtracting one nanosecond.
     */
    private static String exclusiveUpperIso(Instant before) {
        return before.minusNanos(1).toString();
    }

    @Override
    public void putBar(HABar bar, Optional<Long> ttl) {
        client.putItem(PutItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .item(buildItem(bar, ttl))
                .build());
    }

    @Override
    public void snapshotReplace(String instrumentId, Timeframe tf, HABar newBar, Optional<Long> ttl) {
        List<HABar> existing = listAll(instrumentId, tf);
        if (existing.size() < TXN_MAX) {
            List<TransactWriteItem> ops = new ArrayList<>(existing.size() + 1);
            for (HABar e : existing) {
                ops.add(TransactWriteItem.builder()
                        .delete(Delete.builder()
                                .tableName(tableConfig.getTableName())
                                .key(keyFor(e))
                                .build())
                        .build());
            }
            ops.add(TransactWriteItem.builder()
                    .put(Put.builder()
                            .tableName(tableConfig.getTableName())
                            .item(buildItem(newBar, ttl))
                            .build())
                    .build());
            client.transactWriteItems(
                    TransactWriteItemsRequest.builder().transactItems(ops).build());
        } else {
            batchDelete(existing);
            client.putItem(PutItemRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .item(buildItem(newBar, ttl))
                    .build());
        }
    }

    @Override
    public List<HABar> listAll(String instrumentId, Timeframe tf) {
        List<HABar> out = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder q = QueryRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                    .expressionAttributeValues(Map.of(
                            ":pk", s(Keys.instrumentPk(instrumentId)),
                            ":sk", s("HA#" + tf.wire() + "#")));
            if (startKey != null) {
                q.exclusiveStartKey(startKey);
            }
            QueryResponse resp = client.query(q.build());
            for (Map<String, AttributeValue> item : resp.items()) {
                out.add(toBar(item));
            }
            startKey = resp.hasLastEvaluatedKey() ? resp.lastEvaluatedKey() : null;
        } while (startKey != null);
        return out;
    }

    @Override
    public void deleteAll(String instrumentId, Timeframe tf) {
        batchDelete(listAll(instrumentId, tf));
    }

    private void batchDelete(List<HABar> bars) {
        for (int i = 0; i < bars.size(); i += BATCH_DELETE_CHUNK) {
            List<WriteRequest> chunk = new ArrayList<>();
            for (int j = i; j < Math.min(i + BATCH_DELETE_CHUNK, bars.size()); j++) {
                chunk.add(WriteRequest.builder()
                        .deleteRequest(
                                DeleteRequest.builder().key(keyFor(bars.get(j))).build())
                        .build());
            }
            BatchWriteRetry.execute(client, tableConfig.getTableName(), chunk);
        }
    }

    private Map<String, AttributeValue> keyFor(HABar b) {
        return Map.of(
                "pk", s(Keys.instrumentPk(b.instrumentId())),
                "sk", s("HA#" + b.timeframe().wire() + "#" + b.barTime().toString()));
    }

    private Map<String, AttributeValue> buildItem(HABar b, Optional<Long> ttl) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(Keys.instrumentPk(b.instrumentId())));
        item.put("sk", s("HA#" + b.timeframe().wire() + "#" + b.barTime().toString()));
        item.put("entity", s("HA"));
        item.put("instrument_id", s(b.instrumentId()));
        item.put("timeframe", s(b.timeframe().wire()));
        item.put("bar_time", s(b.barTime().toString()));
        item.put("ha_open", n(b.haOpen().toPlainString()));
        item.put("ha_high", n(b.haHigh().toPlainString()));
        item.put("ha_low", n(b.haLow().toPlainString()));
        item.put("ha_close", n(b.haClose().toPlainString()));
        item.put("computed_at", s(b.computedAt().toString()));
        ttl.ifPresent(t -> item.put("ttl", n(Long.toString(t))));
        return item;
    }

    private HABar toBar(Map<String, AttributeValue> item) {
        return new HABar(
                item.get("instrument_id").s(),
                Timeframe.fromWire(item.get("timeframe").s()),
                Instant.parse(item.get("bar_time").s()),
                new BigDecimal(item.get("ha_open").n()),
                new BigDecimal(item.get("ha_high").n()),
                new BigDecimal(item.get("ha_low").n()),
                new BigDecimal(item.get("ha_close").n()),
                Instant.parse(item.get("computed_at").s()));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(String v) {
        return AttributeValue.fromN(v);
    }
}
