package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.PatternsConfig;
import com.heikinashi.monitoring.domain.StoragePolicy;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.DuplicateInstrumentException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
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
 * <p>Block 1 spec, see CLAUDE.md §4. {@link #register(Instrument, InstrumentConfig)}
 * performs an atomic TransactWriteItems for META + CONFIG + UNIQUE_LOCK with
 * {@code attribute_not_exists(pk)} on each, mapping a transaction cancellation
 * on the LOCK to a {@link DuplicateInstrumentException}.
 *
 * <p>Block 2 spec, see CLAUDE.md §5. {@link #updateConfig(String, InstrumentConfig)}
 * performs a PutItem on the CONFIG sk with {@code attribute_exists(pk)} so that
 * a missing META yields {@link InstrumentNotFoundException}; concurrent updates
 * are last-write-wins.
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
    public void register(Instrument instrument, InstrumentConfig defaultConfig) {
        Map<String, AttributeValue> metaItem = buildMetaItem(instrument);
        Map<String, AttributeValue> configItem = buildConfigItem(instrument.id(), defaultConfig);
        Map<String, AttributeValue> lockItem = buildLockItem(instrument);

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

    @Override
    public Optional<InstrumentConfig> findConfigById(String id) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .key(Map.of("pk", s(Keys.instrumentPk(id)), "sk", s(Keys.SK_CONFIG)))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toConfig(response.item()));
    }

    @Override
    public void updateConfig(String id, InstrumentConfig updated) {
        Map<String, AttributeValue> item = buildConfigItem(id, updated);
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(tableConfig.getTableName())
                    .item(item)
                    .conditionExpression("attribute_exists(pk)")
                    .build());
        } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            throw new InstrumentNotFoundException(id);
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

    private Map<String, AttributeValue> buildMetaItem(Instrument instrument) {
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
        return metaItem;
    }

    private Map<String, AttributeValue> buildLockItem(Instrument instrument) {
        Map<String, AttributeValue> lockItem = new HashMap<>();
        lockItem.put("pk", s(Keys.tickerLockPk(instrument.exchange(), instrument.ticker())));
        lockItem.put("sk", s(Keys.SK_LOCK));
        lockItem.put("entity", s(Keys.ENTITY_LOCK));
        lockItem.put("instrument_id", s(instrument.id()));
        lockItem.put("created_at", s(instrument.createdAt().toString()));
        return lockItem;
    }

    private Map<String, AttributeValue> buildConfigItem(String instrumentId, InstrumentConfig cfg) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(Keys.instrumentPk(instrumentId)));
        item.put("sk", s(Keys.SK_CONFIG));
        item.put("entity", s(Keys.ENTITY_CONFIG));
        item.put("storage_policy", s(cfg.storagePolicy().wire()));
        cfg.rollingWindowSize().ifPresent(w -> item.put("rolling_window_size", n(Integer.toString(w))));
        if (!cfg.trackedTimeframes().isEmpty()) {
            List<String> tfs = new ArrayList<>(cfg.trackedTimeframes().size());
            for (Timeframe t : cfg.trackedTimeframes()) {
                tfs.add(t.wire());
            }
            item.put("tracked_timeframes", AttributeValue.fromSs(tfs));
        }
        item.put("patterns", AttributeValue.fromM(buildPatternsMap(cfg.patterns())));
        if (!cfg.recipients().isEmpty()) {
            item.put("recipients", AttributeValue.fromSs(new ArrayList<>(cfg.recipients())));
        }
        item.put("enable_chart", AttributeValue.fromBool(cfg.enableChart()));
        item.put("enable_ai_analysis", AttributeValue.fromBool(cfg.enableAiAnalysis()));
        item.put("created_at", s(cfg.createdAt().toString()));
        item.put("updated_at", s(cfg.updatedAt().toString()));
        return item;
    }

    private Map<String, AttributeValue> buildPatternsMap(PatternsConfig p) {
        Map<String, AttributeValue> m = new HashMap<>();

        Map<String, AttributeValue> cc = new HashMap<>();
        cc.put("enabled", AttributeValue.fromBool(p.colorChange().enabled()));
        cc.put("min_streak_length", n(Integer.toString(p.colorChange().minStreakLength())));
        m.put("color_change", AttributeValue.fromM(cc));

        Map<String, AttributeValue> sc = new HashMap<>();
        sc.put("enabled", AttributeValue.fromBool(p.strongCandle().enabled()));
        sc.put("wick_tolerance", n(p.strongCandle().wickTolerance().toPlainString()));
        sc.put("min_body_ratio", n(p.strongCandle().minBodyRatio().toPlainString()));
        m.put("strong_candle", AttributeValue.fromM(sc));

        Map<String, AttributeValue> dj = new HashMap<>();
        dj.put("enabled", AttributeValue.fromBool(p.doji().enabled()));
        dj.put("max_body_ratio", n(p.doji().maxBodyRatio().toPlainString()));
        m.put("doji", AttributeValue.fromM(dj));

        return m;
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

    private InstrumentConfig toConfig(Map<String, AttributeValue> item) {
        Optional<Integer> window =
                Optional.ofNullable(item.get("rolling_window_size")).map(av -> Integer.parseInt(av.n()));
        Set<Timeframe> tfs = new LinkedHashSet<>();
        AttributeValue tfsAv = item.get("tracked_timeframes");
        if (tfsAv != null && tfsAv.hasSs()) {
            for (String wire : tfsAv.ss()) {
                tfs.add(Timeframe.fromWire(wire));
            }
        }
        Set<String> recipients = new LinkedHashSet<>();
        AttributeValue rcptAv = item.get("recipients");
        if (rcptAv != null && rcptAv.hasSs()) {
            recipients.addAll(rcptAv.ss());
        }
        Map<String, AttributeValue> patternsM = item.get("patterns").m();
        Map<String, AttributeValue> ccM = patternsM.get("color_change").m();
        Map<String, AttributeValue> scM = patternsM.get("strong_candle").m();
        Map<String, AttributeValue> djM = patternsM.get("doji").m();
        PatternsConfig patterns = new PatternsConfig(
                new PatternsConfig.ColorChange(
                        ccM.get("enabled").bool(),
                        Integer.parseInt(ccM.get("min_streak_length").n())),
                new PatternsConfig.StrongCandle(
                        scM.get("enabled").bool(),
                        new BigDecimal(scM.get("wick_tolerance").n()),
                        new BigDecimal(scM.get("min_body_ratio").n())),
                new PatternsConfig.Doji(
                        djM.get("enabled").bool(),
                        new BigDecimal(djM.get("max_body_ratio").n())));
        return new InstrumentConfig(
                StoragePolicy.fromWire(item.get("storage_policy").s()),
                window,
                tfs,
                patterns,
                recipients,
                item.get("enable_chart").bool(),
                item.get("enable_ai_analysis").bool(),
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
