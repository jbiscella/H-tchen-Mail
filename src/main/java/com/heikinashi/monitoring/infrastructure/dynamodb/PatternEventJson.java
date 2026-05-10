package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Serializes a {@link PatternEvent} to a JSON string for storage in the
 * {@code event} attribute of a {@code PENDING_ALERT} item (CLAUDE.md §2 / §9).
 *
 * <p>Numeric domain values are written as JSON strings (BigDecimal) so the
 * round-trip is lossless regardless of Jackson's default number coercion.
 */
final class PatternEventJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    private PatternEventJson() {}

    static String toJson(PatternEvent event) {
        try {
            return MAPPER.writeValueAsString(toMap(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize PatternEvent", e);
        }
    }

    static PatternEvent fromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = MAPPER.readValue(json, Map.class);
            return fromMap(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize PatternEvent", e);
        }
    }

    static Map<String, Object> toMap(PatternEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instrument_id", event.instrumentId());
        m.put("ticker", event.ticker());
        m.put("exchange", event.exchange());
        m.put("timeframe", event.timeframe().wire());
        m.put("bar_time", event.barTime().toString());
        m.put("pattern", event.pattern().wire());
        m.put("subtype", event.subtype().wire());
        m.put("params_used", paramsToMap(event.paramsUsed()));
        m.put("bar_snapshot", barSnapshotToMap(event.barSnapshot()));
        m.put("detected_at", event.detectedAt().toString());
        return m;
    }

    static PatternEvent fromMap(Map<String, Object> m) {
        return new PatternEvent(
                (String) m.get("instrument_id"),
                (String) m.get("ticker"),
                (String) m.get("exchange"),
                Timeframe.fromWire((String) m.get("timeframe")),
                Instant.parse((String) m.get("bar_time")),
                PatternKind.fromWire((String) m.get("pattern")),
                PatternSubtype.valueOf(((String) m.get("subtype")).toUpperCase(Locale.ROOT)),
                paramsFromMap(asMap(m.get("params_used"))),
                barSnapshotFromMap(asMap(m.get("bar_snapshot"))),
                Instant.parse((String) m.get("detected_at")));
    }

    private static Map<String, Object> paramsToMap(Map<String, Object> params) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            Object v = e.getValue();
            if (v instanceof BigDecimal bd) {
                out.put(e.getKey(), bd.toPlainString());
            } else if (v instanceof Number n) {
                out.put(e.getKey(), n.toString());
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private static Map<String, Object> paramsFromMap(Map<String, Object> map) {
        // params_used is already in a map shape; we keep it as-is.
        return map == null ? Map.of() : Map.copyOf(map);
    }

    private static Map<String, Object> barSnapshotToMap(BarSnapshot bs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", bs.open().toPlainString());
        m.put("high", bs.high().toPlainString());
        m.put("low", bs.low().toPlainString());
        m.put("close", bs.close().toPlainString());
        m.put("volume", bs.volume().map(BigDecimal::toPlainString).orElse(null));
        m.put("ha_open", bs.haOpen().toPlainString());
        m.put("ha_high", bs.haHigh().toPlainString());
        m.put("ha_low", bs.haLow().toPlainString());
        m.put("ha_close", bs.haClose().toPlainString());
        return m;
    }

    private static BarSnapshot barSnapshotFromMap(Map<String, Object> m) {
        Optional<BigDecimal> volume = Optional.ofNullable(m.get("volume")).map(v -> new BigDecimal(v.toString()));
        return new BarSnapshot(
                bd(m.get("open")),
                bd(m.get("high")),
                bd(m.get("low")),
                bd(m.get("close")),
                volume,
                bd(m.get("ha_open")),
                bd(m.get("ha_high")),
                bd(m.get("ha_low")),
                bd(m.get("ha_close")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static BigDecimal bd(Object o) {
        if (o == null) {
            throw new IllegalArgumentException("required BigDecimal field is null");
        }
        return new BigDecimal(o.toString());
    }
}
