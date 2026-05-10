package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

class BatchWriteRetryTest {

    private static final String TABLE = "monitoring-test";

    private static WriteRequest delete(String pk, String sk) {
        return WriteRequest.builder()
                .deleteRequest(DeleteRequest.builder()
                        .key(Map.of(
                                "pk", AttributeValue.builder().s(pk).build(),
                                "sk", AttributeValue.builder().s(sk).build()))
                        .build())
                .build();
    }

    @Test
    void empty_first_response_returns_immediately() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());

        BatchWriteRetry.execute(client, TABLE, List.of(delete("pk1", "sk1")));

        verify(client, times(1)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void retries_unprocessed_then_succeeds() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        WriteRequest unprocessed = delete("pk2", "sk2");
        when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of(TABLE, List.of(unprocessed)))
                        .build())
                .thenReturn(BatchWriteItemResponse.builder().build());

        BatchWriteRetry.execute(client, TABLE, List.of(delete("pk1", "sk1"), unprocessed));

        verify(client, times(2)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void gives_up_after_max_attempts() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        WriteRequest unprocessed = delete("pk3", "sk3");
        when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of(TABLE, List.of(unprocessed)))
                        .build());

        assertThatThrownBy(() -> BatchWriteRetry.execute(client, TABLE, List.of(unprocessed)))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasRootCauseMessage("BatchWriteItem unprocessed after 4 attempts");

        // MAX_ATTEMPTS = 4
        verify(client, times(4)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void empty_writes_is_a_noop() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        BatchWriteRetry.execute(client, TABLE, List.of());
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void carries_only_remaining_writes_to_the_next_attempt() {
        DynamoDbClient client = Mockito.mock(DynamoDbClient.class);
        WriteRequest left = delete("pkA", "skA");
        WriteRequest right = delete("pkB", "skB");
        // First call: both submitted, only `right` reported unprocessed.
        // Second call: response is empty -> done.
        when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of(TABLE, List.of(right)))
                        .build())
                .thenReturn(BatchWriteItemResponse.builder().build());

        BatchWriteRetry.execute(client, TABLE, List.of(left, right));

        org.mockito.ArgumentCaptor<BatchWriteItemRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(client, times(2)).batchWriteItem(captor.capture());

        List<BatchWriteItemRequest> calls = captor.getAllValues();
        assertThat(calls.get(0).requestItems().get(TABLE)).hasSize(2);
        assertThat(calls.get(1).requestItems().get(TABLE)).hasSize(1);
    }
}
