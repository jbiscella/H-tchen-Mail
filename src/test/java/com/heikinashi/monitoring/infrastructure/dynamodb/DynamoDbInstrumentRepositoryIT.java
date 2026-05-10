package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.error.DuplicateInstrumentException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Integration test for {@link DynamoDbInstrumentRepository} backed by
 * LocalStack via Testcontainers. Validates that the production DynamoDB
 * adapter satisfies the same contract that the in-memory fake provides
 * for Block 1 (CLAUDE.md §4): atomic register, conflict detection,
 * GSI1-based listing with cursor pagination, status updates that flip
 * gsi1Pk, and idempotent multi-step hard delete.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbInstrumentRepositoryIT {

    private static final String TABLE = "monitoring-it";

    private static LocalStackContainer localstack;
    private static DynamoDbClient client;
    private static DynamoDbInstrumentRepository repo;

    @BeforeAll
    void startLocalstack() {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7"))
                .withServices(LocalStackContainer.Service.DYNAMODB);
        localstack.start();

        client = DynamoDbClient.builder()
                .endpointOverride(URI.create(localstack.getEndpoint().toString()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();

        DynamoTableConfig tableConfig = new DynamoTableConfig();
        tableConfig.setTableName(TABLE);
        tableConfig.setGsi1Name("gsi_status");
        tableConfig.setGsi2Name("gsi_retry_due");

        createTable();
        repo = new DynamoDbInstrumentRepository(client, tableConfig);
    }

    @AfterAll
    void stopLocalstack() {
        if (client != null) {
            client.close();
        }
        if (localstack != null) {
            localstack.stop();
        }
    }

    @BeforeEach
    void wipeTable() {
        client.deleteTable(b -> b.tableName(TABLE));
        createTable();
    }

    private static void createTable() {
        client.createTable(CreateTableRequest.builder()
                .tableName(TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName("pk")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("sk")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("gsi1Pk")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("gsi1Sk")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("gsi2Pk")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("gsi2Sk")
                                .attributeType(ScalarAttributeType.S)
                                .build())
                .keySchema(
                        KeySchemaElement.builder()
                                .attributeName("pk")
                                .keyType(KeyType.HASH)
                                .build(),
                        KeySchemaElement.builder()
                                .attributeName("sk")
                                .keyType(KeyType.RANGE)
                                .build())
                .globalSecondaryIndexes(
                        GlobalSecondaryIndex.builder()
                                .indexName("gsi_status")
                                .keySchema(
                                        KeySchemaElement.builder()
                                                .attributeName("gsi1Pk")
                                                .keyType(KeyType.HASH)
                                                .build(),
                                        KeySchemaElement.builder()
                                                .attributeName("gsi1Sk")
                                                .keyType(KeyType.RANGE)
                                                .build())
                                .projection(Projection.builder()
                                        .projectionType(ProjectionType.ALL)
                                        .build())
                                .build(),
                        GlobalSecondaryIndex.builder()
                                .indexName("gsi_retry_due")
                                .keySchema(
                                        KeySchemaElement.builder()
                                                .attributeName("gsi2Pk")
                                                .keyType(KeyType.HASH)
                                                .build(),
                                        KeySchemaElement.builder()
                                                .attributeName("gsi2Sk")
                                                .keyType(KeyType.RANGE)
                                                .build())
                                .projection(Projection.builder()
                                        .projectionType(ProjectionType.ALL)
                                        .build())
                                .build())
                .build());
    }

    // -------- contract tests -------------------------------------------------

    @Test
    void register_then_findById_round_trips_the_instrument() {
        Instrument inst = sampleInstrument("AAPL", "NASDAQ");
        repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));

        Optional<Instrument> loaded = repo.findById(inst.id());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().ticker()).isEqualTo("AAPL");
        assertThat(loaded.get().exchange()).isEqualTo("NASDAQ");
        assertThat(loaded.get().status()).isEqualTo(InstrumentStatus.ACTIVE);
    }

    @Test
    void register_atomically_writes_meta_config_and_lock() {
        Instrument inst = sampleInstrument("AAPL", "NASDAQ");
        repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));

        // META + LOCK + CONFIG all readable.
        assertThat(repo.findById(inst.id())).isPresent();
        assertThat(repo.findConfigById(inst.id())).isPresent();
    }

    @Test
    void register_duplicate_ticker_exchange_pair_raises_duplicate_instrument() {
        Instrument first = sampleInstrument("AAPL", "NASDAQ");
        repo.register(first, InstrumentConfig.defaults(first.createdAt()));

        Instrument second = sampleInstrument("AAPL", "NASDAQ");
        assertThatThrownBy(() -> repo.register(second, InstrumentConfig.defaults(second.createdAt())))
                .isInstanceOf(DuplicateInstrumentException.class);
    }

    @Test
    void listByStatus_filters_by_gsi1_and_paginates() {
        for (int i = 0; i < 7; i++) {
            Instrument inst = sampleInstrument("T" + i, "NASDAQ");
            repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));
        }

        Page<Instrument> first = repo.listByStatus(InstrumentStatus.ACTIVE, 3, Optional.empty());
        assertThat(first.items()).hasSize(3);
        assertThat(first.nextCursor()).isPresent();

        Page<Instrument> second = repo.listByStatus(InstrumentStatus.ACTIVE, 3, first.nextCursor());
        assertThat(second.items()).hasSize(3);

        Page<Instrument> third = repo.listByStatus(InstrumentStatus.ACTIVE, 3, second.nextCursor());
        assertThat(third.items()).hasSize(1);
        assertThat(third.nextCursor()).isEmpty();
    }

    @Test
    void updateStatus_flips_gsi1Pk_so_subsequent_listing_finds_it_under_archived() {
        Instrument inst = sampleInstrument("AAPL", "NASDAQ");
        repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));

        repo.updateStatus(inst.id(), InstrumentStatus.ARCHIVED, Instant.parse("2026-05-08T00:00:00Z"));

        assertThat(repo.listByStatus(InstrumentStatus.ACTIVE, 10, Optional.empty())
                        .items())
                .isEmpty();
        List<Instrument> archived = repo.listByStatus(InstrumentStatus.ARCHIVED, 10, Optional.empty())
                .items();
        assertThat(archived).hasSize(1);
        assertThat(archived.get(0).status()).isEqualTo(InstrumentStatus.ARCHIVED);
    }

    @Test
    void updateMetadata_on_missing_instrument_raises_not_found() {
        Instrument ghost = sampleInstrument("AAPL", "NASDAQ");
        assertThatThrownBy(() -> repo.updateMetadata(ghost)).isInstanceOf(InstrumentNotFoundException.class);
    }

    @Test
    void hardDelete_removes_meta_config_and_lock() {
        Instrument inst = sampleInstrument("AAPL", "NASDAQ");
        repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));

        repo.hardDelete(inst.id());

        assertThat(repo.findById(inst.id())).isEmpty();
        assertThat(repo.findConfigById(inst.id())).isEmpty();

        // Lock released — re-registering the same (ticker, exchange) succeeds.
        Instrument reborn = sampleInstrument("AAPL", "NASDAQ");
        repo.register(reborn, InstrumentConfig.defaults(reborn.createdAt()));
        assertThat(repo.findById(reborn.id())).isPresent();
    }

    @Test
    void hardDelete_is_idempotent_on_a_never_existed_id() {
        repo.hardDelete("never-existed");
        repo.hardDelete("never-existed");
        // No assertion needed — idempotency means no exception.
    }

    @Test
    void updateConfig_on_missing_instrument_raises_not_found() {
        InstrumentConfig cfg = InstrumentConfig.defaults(Instant.parse("2026-05-07T22:00:00Z"));
        assertThatThrownBy(() -> repo.updateConfig("missing-id", cfg)).isInstanceOf(InstrumentNotFoundException.class);
    }

    // -------- helpers --------------------------------------------------------

    private static Instrument sampleInstrument(String ticker, String exchange) {
        Instant now = Instant.parse("2026-05-07T22:00:00Z");
        return new Instrument(
                UUID.randomUUID().toString(),
                ticker,
                exchange,
                Optional.of(ticker + " Inc."),
                Optional.of("USD"),
                InstrumentStatus.ACTIVE,
                now,
                now);
    }
}
