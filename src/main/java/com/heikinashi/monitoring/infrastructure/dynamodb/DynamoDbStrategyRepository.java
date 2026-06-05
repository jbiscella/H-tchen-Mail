package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyRepository;
import com.heikinashi.monitoring.infrastructure.strategy.StrategyJsonImporter;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * DynamoDB single-table adapter for the {@link StrategyRepository} port
 * (CLAUDE.md §2 STRATEGY item, Component 1b SI-3a).
 *
 * <p>An instrument has at most one STRATEGY item ({@code pk=INSTRUMENT#<id>,
 * sk=STRATEGY}) storing the imported strategy as the <b>verbatim importer
 * JSON</b>. The repository never maps scenarios / conditions field-by-field: it
 * stores the JSON string and reparses it on read via
 * {@link StrategyJsonImporter#fromJson}, so the stored shape always matches the
 * importer's authoritative schema and a strategy round-trips byte-for-byte.
 *
 * <p>Absent item → {@link Optional#empty()} (the instrument keeps legacy
 * fixed-pattern detection, SI-3b). A corrupt stored JSON fails loud
 * ({@code StrategyImportException}) rather than silently degrading. {@link #save}
 * is an idempotent last-write-wins {@code PutItem}: re-importing overwrites the
 * single STRATEGY item.
 */
@Singleton
public class DynamoDbStrategyRepository implements StrategyRepository {

    private static final StrategyJsonImporter IMPORTER = new StrategyJsonImporter();

    private final DynamoDbClient client;
    private final DynamoTableConfig tableConfig;

    public DynamoDbStrategyRepository(DynamoDbClient client, DynamoTableConfig tableConfig) {
        this.client = client;
        this.tableConfig = tableConfig;
    }

    @Override
    public Optional<Strategy> findByInstrumentId(String instrumentId) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .key(Map.of("pk", s(Keys.instrumentPk(instrumentId)), "sk", s(Keys.SK_STRATEGY)))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(response.item()));
    }

    /**
     * Persists the imported strategy JSON for an instrument as the single
     * STRATEGY item. Idempotent last-write-wins ({@code PutItem}, no condition);
     * {@code strategyName} is denormalized for convenience (authoritative copy is
     * inside {@code strategyJson}).
     */
    public void save(String instrumentId, String strategyJson, String strategyName, Instant now) {
        client.putItem(PutItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .item(toItem(instrumentId, strategyJson, strategyName, now))
                .build());
    }

    /** Builds the STRATEGY item attributes (CLAUDE.md §2). Pure; no client. */
    public static Map<String, AttributeValue> toItem(
            String instrumentId, String strategyJson, String strategyName, Instant now) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(Keys.instrumentPk(instrumentId)));
        item.put("sk", s(Keys.SK_STRATEGY));
        item.put("entity", s(Keys.ENTITY_STRATEGY));
        item.put("strategy_json", s(strategyJson));
        item.put("name", s(strategyName));
        item.put("created_at", s(now.toString()));
        item.put("updated_at", s(now.toString()));
        return item;
    }

    /** Reparses the strategy stored in a STRATEGY item. Pure; no client. */
    public static Strategy fromItem(Map<String, AttributeValue> item) {
        return IMPORTER.fromJson(item.get("strategy_json").s());
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }
}
