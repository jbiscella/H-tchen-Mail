package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.OhlcRepository;
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
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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
 * DynamoDB single-table adapter for {@link OhlcRepository}.
 *
 * <p>Items live under {@code pk = INSTRUMENT#<id>}, {@code sk = OHLC#<tf>#<bar_time_iso>}.
 * Idempotent put uses {@code attribute_not_exists(pk)}. SNAPSHOT_ONLY truncate-and-put
 * uses a TransactWriteItems when ≤ 24 existing bars, falling back to a non-atomic
 * batched delete + put for larger sets (CLAUDE.md §6 trade-off).
 */
@Singleton
public class DynamoDbOhlcRepository implements OhlcRepository {

    private static final int TXN_MAX = 25;
    private static final int BATCH_DELETE_CHUNK = 25;

    private final DynamoDbClient client;
    private final DynamoTableConfig tableConfig;

    public DynamoDbOhlcRepository(DynamoDbClient client, DynamoTableConfig tableConfig) {
        this.client = client;
        this.tableConfig = tableConfig;
    }

    @Override
    public Optional<OHLCBar> findLatest(String instrumentId, Timeframe tf) {
        QueryResponse resp = client.query(QueryRequest.builder()
                .tableName(tableConfig.getTableName())
                .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", s(Keys.instrumentPk(instrumentId)),
                        ":sk", s("OHLC#" + tf.wire() + "#")))
                .scanIndexForward(false)
                .limit(1)
                .build());
        if (resp.items().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toBar(resp.items().get(0)));
    }

    @Override
    public boolean putBar(OHLCBar bar, Optional<Long> ttl) {
        Map<String, AttributeValue> item = buildItem(bar, ttl);
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public void snapshotReplace(String instrumentId, Timeframe tf, OHLCBar newBar, Optional<Long> ttl) {
        List<OHLCBar> existing = listAll(instrumentId, tf);
        if (existing.size() < TXN_MAX) {
            List<TransactWriteItem> ops = new ArrayList<>(existing.size() + 1);
            for (OHLCBar e : existing) {
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
    public List<OHLCBar> listAll(String instrumentId, Timeframe tf) {
        List<OHLCBar> out = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder q = QueryRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                    .expressionAttributeValues(Map.of(
                            ":pk", s(Keys.instrumentPk(instrumentId)),
                            ":sk", s("OHLC#" + tf.wire() + "#")));
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

    private void batchDelete(List<OHLCBar> bars) {
        for (int i = 0; i < bars.size(); i += BATCH_DELETE_CHUNK) {
            List<WriteRequest> chunk = new ArrayList<>();
            for (int j = i; j < Math.min(i + BATCH_DELETE_CHUNK, bars.size()); j++) {
                chunk.add(WriteRequest.builder()
                        .deleteRequest(
                                DeleteRequest.builder().key(keyFor(bars.get(j))).build())
                        .build());
            }
            client.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(tableConfig.getTableName(), chunk))
                    .build());
        }
    }

    private Map<String, AttributeValue> keyFor(OHLCBar b) {
        return Map.of(
                "pk", s(Keys.instrumentPk(b.instrumentId())),
                "sk", s("OHLC#" + b.timeframe().wire() + "#" + b.barTime().toString()));
    }

    private Map<String, AttributeValue> buildItem(OHLCBar b, Optional<Long> ttl) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(Keys.instrumentPk(b.instrumentId())));
        item.put("sk", s("OHLC#" + b.timeframe().wire() + "#" + b.barTime().toString()));
        item.put("entity", s("OHLC"));
        item.put("instrument_id", s(b.instrumentId()));
        item.put("timeframe", s(b.timeframe().wire()));
        item.put("bar_time", s(b.barTime().toString()));
        item.put("open", n(b.open().toPlainString()));
        item.put("high", n(b.high().toPlainString()));
        item.put("low", n(b.low().toPlainString()));
        item.put("close", n(b.close().toPlainString()));
        b.volume().ifPresent(v -> item.put("volume", n(v.toPlainString())));
        item.put("source", s(b.source()));
        item.put("ingested_at", s(b.ingestedAt().toString()));
        ttl.ifPresent(t -> item.put("ttl", n(Long.toString(t))));
        return item;
    }

    private OHLCBar toBar(Map<String, AttributeValue> item) {
        return new OHLCBar(
                item.get("instrument_id").s(),
                Timeframe.fromWire(item.get("timeframe").s()),
                Instant.parse(item.get("bar_time").s()),
                new BigDecimal(item.get("open").n()),
                new BigDecimal(item.get("high").n()),
                new BigDecimal(item.get("low").n()),
                new BigDecimal(item.get("close").n()),
                Optional.ofNullable(item.get("volume")).map(av -> new BigDecimal(av.n())),
                item.get("source").s(),
                Instant.parse(item.get("ingested_at").s()));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(String v) {
        return AttributeValue.fromN(v);
    }
}
