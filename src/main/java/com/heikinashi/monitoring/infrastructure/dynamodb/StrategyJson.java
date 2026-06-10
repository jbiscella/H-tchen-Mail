package com.heikinashi.monitoring.infrastructure.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serializes a {@link Strategy} to a JSON string for the {@code strategy}
 * attribute of a {@code STRATEGY_PENDING_ALERT} item (CLAUDE.md §2 / §9 Component
 * 1c SI-3c.3). This is the <b>snapshot of the strategy that fired</b>, so the
 * retry poller re-renders the chart from the rules that actually triggered rather
 * than the live {@code STRATEGY} item (which may have been re-imported, edited, or
 * deleted in the meantime). A small, internal round-trip representation of the
 * domain {@link Strategy}; not the importer's external JSON schema.
 */
final class StrategyJson {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private StrategyJson() {}

    static String toJson(Strategy strategy) {
        try {
            return MAPPER.writeValueAsString(toMap(strategy));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Strategy", e);
        }
    }

    static Strategy fromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = MAPPER.readValue(json, Map.class);
            return fromMap(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize Strategy", e);
        }
    }

    static Map<String, Object> toMap(Strategy strategy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", strategy.name());
        List<Map<String, Object>> scenarios =
                new ArrayList<>(strategy.scenarios().size());
        for (StrategyScenario s : strategy.scenarios()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("name", s.name());
            sm.put("role", s.role());
            sm.put("conditions", new ArrayList<>(s.conditions()));
            s.positionPrecondition().ifPresent(v -> sm.put("position_precondition", v));
            s.stopLoss().ifPresent(v -> sm.put("stop_loss", v));
            s.takeProfit().ifPresent(v -> sm.put("take_profit", v));
            scenarios.add(sm);
        }
        m.put("scenarios", scenarios);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Strategy fromMap(Map<String, Object> m) {
        List<Map<String, Object>> rawScenarios = (List<Map<String, Object>>) m.get("scenarios");
        List<StrategyScenario> scenarios = new ArrayList<>(rawScenarios.size());
        for (Map<String, Object> sm : rawScenarios) {
            scenarios.add(new StrategyScenario(
                    (String) sm.get("name"),
                    (String) sm.get("role"),
                    (List<String>) sm.get("conditions"),
                    optString(sm.get("position_precondition")),
                    optString(sm.get("stop_loss")),
                    optString(sm.get("take_profit"))));
        }
        return new Strategy((String) m.get("name"), scenarios);
    }

    private static Optional<String> optString(Object v) {
        return v == null ? Optional.empty() : Optional.of((String) v);
    }
}
