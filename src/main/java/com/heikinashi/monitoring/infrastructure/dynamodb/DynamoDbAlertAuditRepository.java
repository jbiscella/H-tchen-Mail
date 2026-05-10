package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.heikinashi.monitoring.domain.AlertAuditRepository;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.PatternEvent;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * DynamoDB single-table adapter for {@link AlertAuditRepository}.
 *
 * <p>Items live under {@code pk = INSTRUMENT#<id>}, {@code sk =
 * ALERT#<bar_time_iso>#<pattern>#<subtype>#<sent_at_ms>}. The audit trail is
 * append-only — there is no read or update path on individual ALERT items —
 * so {@link #recordSentAlert(PatternEvent, AlertEnrichment, Set, List, Instant)}
 * is a single {@code PutItem} call per delivery (CLAUDE.md §2 / §9).
 *
 * <p>Per the spec, {@code ttl} is set to {@code bar_time_epoch + 365 days}
 * so audit retention follows the bar's lifetime.
 */
@Singleton
public class DynamoDbAlertAuditRepository implements AlertAuditRepository {

    private static final long TTL_RETENTION_DAYS = 365L;

    private final DynamoDbClient client;
    private final DynamoTableConfig tableConfig;

    public DynamoDbAlertAuditRepository(DynamoDbClient client, DynamoTableConfig tableConfig) {
        this.client = client;
        this.tableConfig = tableConfig;
    }

    @Override
    public void recordSentAlert(
            PatternEvent event,
            AlertEnrichment enrichment,
            Set<String> deliveredRecipients,
            List<String> sesMessageIds,
            Instant sentAt) {
        client.putItem(PutItemRequest.builder()
                .tableName(tableConfig.getTableName())
                .item(buildItem(event, enrichment, deliveredRecipients, sesMessageIds, sentAt))
                .build());
    }

    private Map<String, AttributeValue> buildItem(
            PatternEvent event,
            AlertEnrichment enrichment,
            Set<String> deliveredRecipients,
            List<String> sesMessageIds,
            Instant sentAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(Keys.instrumentPk(event.instrumentId())));
        item.put(
                "sk",
                s(Keys.alertSk(
                        event.barTime().toString(),
                        event.pattern().wire(),
                        event.subtype().wire(),
                        sentAt.toEpochMilli())));
        item.put("entity", s(Keys.ENTITY_ALERT));
        item.put("instrument_id", s(event.instrumentId()));
        item.put("ticker", s(event.ticker()));
        item.put("exchange", s(event.exchange()));
        item.put("timeframe", s(event.timeframe().wire()));
        item.put("bar_time", s(event.barTime().toString()));
        item.put("pattern", s(event.pattern().wire()));
        item.put("subtype", s(event.subtype().wire()));
        item.put("enrichment", s(enrichment.wire()));
        item.put("sent_at", s(sentAt.toString()));
        if (!deliveredRecipients.isEmpty()) {
            item.put("recipients", AttributeValue.fromSs(new ArrayList<>(deliveredRecipients)));
        }
        if (!sesMessageIds.isEmpty()) {
            item.put("ses_message_ids", AttributeValue.fromSs(new ArrayList<>(sesMessageIds)));
        }
        item.put("params_used", AttributeValue.fromM(paramsToAttributes(event.paramsUsed())));
        long ttl = event.barTime().plus(TTL_RETENTION_DAYS, ChronoUnit.DAYS).getEpochSecond();
        item.put("ttl", n(Long.toString(ttl)));
        return item;
    }

    private Map<String, AttributeValue> paramsToAttributes(Map<String, Object> params) {
        Map<String, AttributeValue> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            m.put(e.getKey(), toAttribute(e.getValue()));
        }
        return m;
    }

    private static AttributeValue toAttribute(Object v) {
        if (v == null) {
            return AttributeValue.fromNul(true);
        }
        if (v instanceof Boolean b) {
            return AttributeValue.fromBool(b);
        }
        if (v instanceof BigDecimal bd) {
            return AttributeValue.fromN(bd.toPlainString());
        }
        if (v instanceof Number nbr) {
            return AttributeValue.fromN(nbr.toString());
        }
        return AttributeValue.fromS(v.toString());
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(String v) {
        return AttributeValue.fromN(v);
    }
}
