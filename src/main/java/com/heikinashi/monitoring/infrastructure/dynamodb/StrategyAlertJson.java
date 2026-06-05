package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serializes a {@link StrategyAlert} to a JSON string for the {@code alert}
 * attribute of a {@code STRATEGY_PENDING_ALERT} item (CLAUDE.md §2 / §9 Component
 * 1c SI-3c.3). Round-trips every matched line (role + verbatim memo) so the
 * retry poller re-sends the same email content.
 */
final class StrategyAlertJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    private StrategyAlertJson() {}

    static String toJson(StrategyAlert alert) {
        try {
            return MAPPER.writeValueAsString(toMap(alert));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize StrategyAlert", e);
        }
    }

    static StrategyAlert fromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = MAPPER.readValue(json, Map.class);
            return fromMap(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize StrategyAlert", e);
        }
    }

    static Map<String, Object> toMap(StrategyAlert alert) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instrument_id", alert.instrumentId());
        m.put("ticker", alert.ticker());
        m.put("exchange", alert.exchange());
        m.put("timeframe", alert.timeframe().wire());
        m.put("bar_time", alert.barTime().toString());
        m.put("strategy_name", alert.strategyName());
        m.put("detected_at", alert.detectedAt().toString());
        List<Map<String, Object>> lines = new ArrayList<>(alert.lines().size());
        for (StrategyAlertLine line : alert.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("scenario_name", line.scenarioName());
            lm.put("role", line.role());
            line.positionPrecondition().ifPresent(v -> lm.put("position_precondition", v));
            line.stopLoss().ifPresent(v -> lm.put("stop_loss", v));
            line.takeProfit().ifPresent(v -> lm.put("take_profit", v));
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    @SuppressWarnings("unchecked")
    static StrategyAlert fromMap(Map<String, Object> m) {
        List<Map<String, Object>> rawLines = (List<Map<String, Object>>) m.get("lines");
        List<StrategyAlertLine> lines = new ArrayList<>(rawLines.size());
        for (Map<String, Object> lm : rawLines) {
            lines.add(new StrategyAlertLine(
                    (String) lm.get("scenario_name"),
                    (String) lm.get("role"),
                    optString(lm.get("position_precondition")),
                    optString(lm.get("stop_loss")),
                    optString(lm.get("take_profit"))));
        }
        return new StrategyAlert(
                (String) m.get("instrument_id"),
                (String) m.get("ticker"),
                (String) m.get("exchange"),
                Timeframe.fromWire((String) m.get("timeframe")),
                Instant.parse((String) m.get("bar_time")),
                (String) m.get("strategy_name"),
                lines,
                Instant.parse((String) m.get("detected_at")));
    }

    private static Optional<String> optString(Object v) {
        return v == null ? Optional.empty() : Optional.of((String) v);
    }
}
