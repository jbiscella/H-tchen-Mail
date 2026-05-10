package com.heikinashi.monitoring.infrastructure.dynamodb;

import java.net.URI;
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
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Shared LocalStack scaffolding for the DynamoDB integration tests.
 *
 * <p>One LocalStack container starts on the first test class load and is
 * reused by every IT in the same surefire / failsafe execution; the JVM
 * shutdown hook stops it. Subclasses get pre-built {@link DynamoDbClient} +
 * {@link DynamoTableConfig} instances and a {@link #wipeTable()} helper that
 * drops and recreates the table between tests for isolation.
 */
abstract class LocalStackITBase {

    protected static final String TABLE_NAME = "monitoring-it";

    protected static final LocalStackContainer LOCALSTACK;
    protected static final DynamoDbClient CLIENT;
    protected static final DynamoTableConfig TABLE_CONFIG;

    static {
        LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7"))
                .withServices(LocalStackContainer.Service.DYNAMODB);
        LOCALSTACK.start();
        Runtime.getRuntime().addShutdownHook(new Thread(LOCALSTACK::stop));

        CLIENT = DynamoDbClient.builder()
                .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .region(Region.of(LOCALSTACK.getRegion()))
                .build();

        TABLE_CONFIG = new DynamoTableConfig();
        TABLE_CONFIG.setTableName(TABLE_NAME);
        TABLE_CONFIG.setGsi1Name("gsi_status");
        TABLE_CONFIG.setGsi2Name("gsi_retry_due");
    }

    protected static void wipeTable() {
        try {
            CLIENT.deleteTable(b -> b.tableName(TABLE_NAME));
        } catch (ResourceNotFoundException ignored) {
            // first call: table does not exist yet
        }
        createTable();
    }

    private static void createTable() {
        CLIENT.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
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
}
