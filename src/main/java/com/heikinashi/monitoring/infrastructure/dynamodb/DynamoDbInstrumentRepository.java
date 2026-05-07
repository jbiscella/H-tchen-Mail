package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.error.DuplicateInstrumentException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * DynamoDB single-table adapter for the {@link InstrumentRepository} port.
 *
 * <p>Block 1 spec, see CLAUDE.md §4. {@link #register(Instrument)} performs an
 * atomic TransactWriteItems for META + CONFIG default + UNIQUE_LOCK with
 * {@code attribute_not_exists(pk)} on each, mapping a transaction
 * cancellation on the LOCK to a {@link DuplicateInstrumentException}.
 *
 * <p>{@link #hardDelete(String)} is idempotent: it paginates through OHLC and
 * HA items deleting them in batches of 25, then deletes META + CONFIG + LOCK
 * via TransactWriteItems. Missing items are silently tolerated.
 */
@Singleton
public class DynamoDbInstrumentRepository implements InstrumentRepository {

    private static final int BATCH_DELETE_CHUNK = 25;

    private final DynamoDbClient client;
    private final DynamoTableConfig tableConfig;

    public DynamoDbInstrumentRepository(DynamoDbClient client, DynamoTableConfig tableConfig) {
        this.client = client;
        this.tableConfig = tableConfig;
    }

    @Override
    public void register(Instrument instrument) {
        Map<String, AttributeValue> metaItem = new HashMap<>();
        metaItem.put("pk", s(Keys.instrumentPk(instrument.id())));
        metaItem.put("sk", s(Keys.SK_META));
        metaItem.put("entity", s(Keys.ENTITY_INSTRUMENT));
        metaItem.put("id", s(instrument.id()));
        metaItem.put("ticker", s(instrument.ticker()));
        metaItem.put("exchange", s(instrument.exchange()));
        instrument.name().ifPresent(n -> metaItem.put("name", s(n)));
        instrument.currency().ifPresent(c -> metaItem.put("currency", s(c)));
        metaItem.put("status", s(instrument.status().wire()));
        metaItem.put("created_at", s(instrument.createdAt().toString()));
        metaItem.put("updated_at", s(instrument.updatedAt().toString()));
        metaItem.put("gsi1Pk", s(Keys.statusGsi1Pk(instrument.status().wire())));
        metaItem.put("gsi1Sk", s(Keys.instrumentGsi1Sk(instrument.id())));

        Map<String, AttributeValue> configItem = new HashMap<>();
        configItem.put("pk", s(Keys.instrumentPk(instrument.id())));
        configItem.put("sk", s(Keys.SK_CONFIG));
        configItem.put("entity", s(Keys.ENTITY_CONFIG));
        configItem.put("storage_policy", s("ROLLING_WINDOW"));
        configItem.put("rolling_window_size", n("200"));
        configItem.put("tracked_timeframes", AttributeValue.fromSs(List.of("1d")));
        configItem.put("enable_chart", AttributeValue.fromBool(true));
        configItem.put("enable_ai_analysis", AttributeValue.fromBool(true));
        configItem.put("created_at", s(instrument.createdAt().toString()));
        configItem.put("updated_at", s(instrument.updatedAt().toString()));

        Map<String, AttributeValue> lockItem = new HashMap<>();
        lockItem.put("pk", s(Keys.tickerLockPk(instrument.exchange(), instrument.ticker())));
        lockItem.put("sk", s(Keys.SK_LOCK));
        lockItem.put("entity", s(Keys.ENTITY_LOCK));
        lockItem.put("instrument_id", s(instrument.id()));
        lockItem.put("created_at", s(instrument.createdAt().toString()));

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(putWithCondAbsent(lockItem), putWithCondAbsent(metaItem), putWithCondAbsent(configItem))
                .build();

        try {
            client.transactWriteItems(request);
        } catch (TransactionCanceledException e) {
            for (CancellationReason reason : e.cancellationReasons()) {
                if ("ConditionalCheckFailed".equals(reason.code())) {
                    throw new DuplicateInstrumentException(instrument.ticker(), instrument.exchange());
                }
            }
            throw e;
        }
    }

    @Override
    public Optional<Instrument> findById(String id) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .key(Map.of("pk", s(Keys.instrumentPk(id)), "sk", s(Keys.SK_META)))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toInstrument(response.item()));
    }

    @Override
    public Page<Instrument> listByStatus(InstrumentStatus status, int pageSize, Optional<String> cursor) {
        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(tableConfig.getTableName())
                .indexName(tableConfig.getGsi1Name())
                .keyConditionExpression("gsi1Pk = :pk")
                .expressionAttributeValues(Map.of(":pk", s(Keys.statusGsi1Pk(status.wire()))))
                .limit(pageSize);
        cursor.flatMap(this::decodeCursor).ifPresent(query::exclusiveStartKey);

        QueryResponse response = client.query(query.build());
        List<Instrument> items = new ArrayList<>(response.items().size());
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(toInstrument(item));
        }
        Optional<String> next = response.hasLastEvaluatedKey()
                ? Optional.of(encodeCursor(response.lastEvaluatedKey()))
                : Optional.empty();
        return new Page<>(items, next);
    }

    @Override
    public void updateMetadata(Instrument updated) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":updated_at", s(updated.updatedAt().toString()));

        StringBuilder set = new StringBuilder("SET updated_at = :updated_at");
        Map<String, String> names = new HashMap<>();

        updated.name().ifPresent(n -> {
            set.append(", #nm = :nm");
            names.put("#nm", "name");
            values.put(":nm", s(n));
        });
        updated.currency().ifPresent(c -> {
            set.append(", currency = :currency");
            values.put(":currency", s(c));
        });

        UpdateItemRequest.Builder builder = UpdateItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .key(Map.of("pk", s(Keys.instrumentPk(updated.id())), "sk", s(Keys.SK_META)))
                .updateExpression(set.toString())
                .conditionExpression("attribute_exists(pk)")
                .expressionAttributeValues(values);
        if (!names.isEmpty()) {
            builder.expressionAttributeNames(names);
        }
        try {
            client.updateItem(builder.build());
        } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            throw new InstrumentNotFoundException(updated.id());
        }
    }

    @Override
    public void updateStatus(String id, InstrumentStatus newStatus, Instant updatedAt) {
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .key(Map.of("pk", s(Keys.instrumentPk(id)), "sk", s(Keys.SK_META)))
                    .updateExpression("SET #st = :st, updated_at = :ts, gsi1Pk = :gsi1pk")
                    .conditionExpression("attribute_exists(pk)")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(
                            ":st", s(newStatus.wire()),
                            ":ts", s(updatedAt.toString()),
                            ":gsi1pk", s(Keys.statusGsi1Pk(newStatus.wire()))))
                    .build());
        } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            throw new InstrumentNotFoundException(id);
        }
    }

    @Override
    public void hardDelete(String id) {
        deleteAllWithSkPrefix(Keys.instrumentPk(id), "OHLC#");
        deleteAllWithSkPrefix(Keys.instrumentPk(id), "HA#");

        Optional<Instrument> existing = findById(id);
        if (existing.isEmpty()) {
            return;
        }
        Instrument inst = existing.get();
        String lockPk = Keys.tickerLockPk(inst.exchange(), inst.ticker());
        String pk = Keys.instrumentPk(id);
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder()
                                    .delete(d -> d.tableName(tableConfig.getTableName())
                                            .key(Map.of("pk", s(pk), "sk", s(Keys.SK_META))))
                                    .build(),
                            TransactWriteItem.builder()
                                    .delete(d -> d.tableName(tableConfig.getTableName())
                                            .key(Map.of("pk", s(pk), "sk", s(Keys.SK_CONFIG))))
                                    .build(),
                            TransactWriteItem.builder()
                                    .delete(d -> d.tableName(tableConfig.getTableName())
                                            .key(Map.of("pk", s(lockPk), "sk", s(Keys.SK_LOCK))))
                                    .build())
                    .build());
        } catch (TransactionCanceledException e) {
            // races with another delete: tolerate, all gone.
        }
    }

    private void deleteAllWithSkPrefix(String pk, String skPrefix) {
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder q = QueryRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                    .expressionAttributeValues(Map.of(":pk", s(pk), ":sk", s(skPrefix)))
                    .projectionExpression("pk, sk");
            if (startKey != null) {
                q.exclusiveStartKey(startKey);
            }
            QueryResponse resp = client.query(q.build());
            if (!resp.items().isEmpty()) {
                List<WriteRequest> chunk = new ArrayList<>(BATCH_DELETE_CHUNK);
                for (Map<String, AttributeValue> item : resp.items()) {
                    chunk.add(WriteRequest.builder()
                            .deleteRequest(DeleteRequest.builder()
                                    .key(Map.of("pk", item.get("pk"), "sk", item.get("sk")))
                                    .build())
                            .build());
                    if (chunk.size() == BATCH_DELETE_CHUNK) {
                        flush(chunk);
                        chunk.clear();
                    }
                }
                if (!chunk.isEmpty()) {
                    flush(chunk);
                }
            }
            startKey = resp.hasLastEvaluatedKey() ? resp.lastEvaluatedKey() : null;
        } while (startKey != null);
    }

    private void flush(List<WriteRequest> writes) {
        Map<String, List<WriteRequest>> request = Map.of(tableConfig.getTableName(), writes);
        var resp = client.batchWriteItem(
                BatchWriteItemRequest.builder().requestItems(request).build());
        Map<String, List<WriteRequest>> unprocessed = resp.unprocessedItems();
        while (unprocessed != null && !unprocessed.isEmpty()) {
            resp = client.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(unprocessed).build());
            unprocessed = resp.unprocessedItems();
        }
    }

    @SuppressWarnings("unused")
    private void deleteSingle(String pk, String sk) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .key(Map.of("pk", s(pk), "sk", s(sk)))
                .build());
    }

    private TransactWriteItem putWithCondAbsent(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableConfig.getTableName())
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)")
                        .build())
                .build();
    }

    private Instrument toInstrument(Map<String, AttributeValue> item) {
        return new Instrument(
                item.get("id").s(),
                item.get("ticker").s(),
                item.get("exchange").s(),
                Optional.ofNullable(item.get("name")).map(AttributeValue::s),
                Optional.ofNullable(item.get("currency")).map(AttributeValue::s),
                InstrumentStatus.fromWire(item.get("status").s()),
                Instant.parse(item.get("created_at").s()),
                Instant.parse(item.get("updated_at").s()));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(String v) {
        return AttributeValue.fromN(v);
    }

    private String encodeCursor(Map<String, AttributeValue> lastKey) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, AttributeValue> e : lastKey.entrySet()) {
            if (!first) {
                sb.append('|');
            }
            sb.append(e.getKey()).append('=').append(e.getValue().s());
            first = false;
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Optional<Map<String, AttributeValue>> decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Map<String, AttributeValue> out = new HashMap<>();
            for (String pair : raw.split("\\|")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    return Optional.empty();
                }
                out.put(pair.substring(0, eq), s(pair.substring(eq + 1)));
            }
            return Optional.of(out);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
