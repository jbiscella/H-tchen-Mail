package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * Submits a {@code BatchWriteItem} call and re-submits whatever the service
 * reports as {@code unprocessedItems} until the set is empty.
 *
 * <p>DynamoDB throttling or transient capacity pressure can cause the service
 * to silently skip a subset of the requested writes; the response carries the
 * unprocessed half so the caller can retry. Ignoring that field — which the
 * original implementations did — causes silent data loss.
 */
final class BatchWriteRetry {

    private static final Logger LOG = LoggerFactory.getLogger(BatchWriteRetry.class);

    private static final int MAX_ATTEMPTS = 4;
    private static final long INITIAL_BACKOFF_MS = 50L;

    private BatchWriteRetry() {}

    static void execute(DynamoDbClient client, String tableName, List<WriteRequest> writes) {
        if (writes == null || writes.isEmpty()) {
            return;
        }
        Map<String, List<WriteRequest>> pending = Map.of(tableName, writes);
        long backoffMs = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            BatchWriteItemResponse response = client.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(pending).build());
            Map<String, List<WriteRequest>> unprocessed = response.unprocessedItems();
            if (unprocessed == null || unprocessed.isEmpty()) {
                return;
            }
            List<WriteRequest> remaining = unprocessed.get(tableName);
            if (remaining == null || remaining.isEmpty()) {
                return;
            }
            LOG.warn("batch_write_unprocessed table={} remaining={} attempt={}", tableName, remaining.size(), attempt);
            if (attempt == MAX_ATTEMPTS) {
                throw new DependencyUnavailableException(
                        "dynamodb", new RuntimeException("BatchWriteItem unprocessed after " + attempt + " attempts"));
            }
            sleepUninterruptibly(backoffMs);
            backoffMs *= 2;
            pending = Map.of(tableName, remaining);
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
