package com.heikinashi.monitoring.infrastructure.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.domain.error.StrategyImportException;
import com.heikinashi.monitoring.domain.strategy.MaKind;
import com.heikinashi.monitoring.domain.strategy.MarketCondition;
import com.heikinashi.monitoring.domain.strategy.PriceField;
import com.heikinashi.monitoring.domain.strategy.Side;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Imports a strategy from H-tchen's own JSON format into the domain
 * {@link Strategy} model (Block 15). This is the single point that translates a
 * named condition into something H-tchen can detect; a condition type with no
 * nachtkrapp equivalent is rejected here with a {@link StrategyImportException}
 * naming it, and no partial monitoring is established.
 *
 * <p>Schema (H-tchen artifact):
 *
 * <pre>{@code
 * {
 *   "name": "ma-cross-long",
 *   "scenarios": [
 *     {
 *       "name": "golden-cross-entry",
 *       "role": "long_entry",
 *       "positionPrecondition": "flat",          // optional, memo only
 *       "stopLoss": "entry * 0.98",              // optional, memo only (verbatim)
 *       "takeProfit": "entry * 1.05",            // optional, memo only (verbatim)
 *       "conditions": [
 *         {"type":"moving_average_cross","fastType":"EMA","fastPeriod":50,
 *          "slowType":"SMA","slowPeriod":200,"side":"bullish","source":"CLOSE"}
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * Condition {@code type}s: {@code color_change}, {@code strong_candle},
 * {@code doji}, {@code moving_average_cross}, {@code rsi_midline_cross}.
 */
@Singleton
public class StrategyJsonImporter {

    private final ObjectMapper mapper = new ObjectMapper();

    public Strategy fromJson(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException e) {
            throw new StrategyImportException("<document>", "not valid JSON: " + e.getMessage());
        }
        String name = text(root, "name").orElseThrow(() -> new StrategyImportException("<document>", "missing 'name'"));
        JsonNode scenariosNode = root.get("scenarios");
        if (scenariosNode == null || !scenariosNode.isArray() || scenariosNode.isEmpty()) {
            throw new StrategyImportException(name, "strategy must declare a non-empty 'scenarios' array");
        }
        List<StrategyScenario> scenarios = new ArrayList<>();
        for (JsonNode s : scenariosNode) {
            scenarios.add(parseScenario(s));
        }
        return new Strategy(name, scenarios);
    }

    private StrategyScenario parseScenario(JsonNode s) {
        String name = text(s, "name").orElseThrow(() -> new StrategyImportException("<scenario>", "missing 'name'"));
        String role = text(s, "role").orElseThrow(() -> new StrategyImportException(name, "scenario missing 'role'"));
        JsonNode conditionsNode = s.get("conditions");
        if (conditionsNode == null || !conditionsNode.isArray() || conditionsNode.isEmpty()) {
            throw new StrategyImportException(name, "scenario must declare a non-empty 'conditions' array");
        }
        List<MarketCondition> conditions = new ArrayList<>();
        for (JsonNode c : conditionsNode) {
            conditions.add(parseCondition(c));
        }
        return new StrategyScenario(
                name, role, conditions, text(s, "positionPrecondition"), text(s, "stopLoss"), text(s, "takeProfit"));
    }

    private MarketCondition parseCondition(JsonNode c) {
        String type = text(c, "type").orElseThrow(() -> new StrategyImportException("<condition>", "missing 'type'"));
        return switch (type) {
            case "color_change" -> new MarketCondition.ColorChange(reqInt(c, type, "minStreakLength"), side(c, type));
            case "strong_candle" ->
                new MarketCondition.StrongCandle(
                        reqDecimal(c, type, "wickTolerance"), reqDecimal(c, type, "minBodyRatio"), side(c, type));
            case "doji" -> new MarketCondition.Doji(reqDecimal(c, type, "maxBodyRatio"));
            case "moving_average_cross" ->
                new MarketCondition.MovingAverageCross(
                        maKind(c, type, "fastType"),
                        reqInt(c, type, "fastPeriod"),
                        maKind(c, type, "slowType"),
                        reqInt(c, type, "slowPeriod"),
                        side(c, type),
                        priceField(c, type));
            case "rsi_midline_cross" ->
                new MarketCondition.RsiMidlineCross(reqInt(c, type, "period"), side(c, type), priceField(c, type));
            default ->
                throw new StrategyImportException(type, "no nachtkrapp DetectionRule equivalent for this condition");
        };
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? Optional.of(v.asText()) : Optional.empty();
    }

    private static int reqInt(JsonNode c, String type, String field) {
        JsonNode v = c.get(field);
        if (v == null || !v.canConvertToInt()) {
            throw new StrategyImportException(type, "missing or non-integer '" + field + "'");
        }
        return v.asInt();
    }

    private static BigDecimal reqDecimal(JsonNode c, String type, String field) {
        JsonNode v = c.get(field);
        if (v == null || !v.isNumber() && !(v.isTextual() && isNumeric(v.asText()))) {
            throw new StrategyImportException(type, "missing or non-numeric '" + field + "'");
        }
        return new BigDecimal(v.asText());
    }

    private static boolean isNumeric(String s) {
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Side side(JsonNode c, String type) {
        String raw = text(c, "side").orElseThrow(() -> new StrategyImportException(type, "missing 'side'"));
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "bullish", "up", "long" -> Side.BULLISH;
            case "bearish", "down", "short" -> Side.BEARISH;
            default -> throw new StrategyImportException(type, "unknown side '" + raw + "'");
        };
    }

    private static MaKind maKind(JsonNode c, String type, String field) {
        String raw = text(c, field).orElseThrow(() -> new StrategyImportException(type, "missing '" + field + "'"));
        try {
            return MaKind.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new StrategyImportException(type, "unknown moving-average kind '" + raw + "'");
        }
    }

    private static PriceField priceField(JsonNode c, String type) {
        String raw = text(c, "source").orElseThrow(() -> new StrategyImportException(type, "missing 'source'"));
        try {
            return PriceField.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new StrategyImportException(type, "unknown price source '" + raw + "'");
        }
    }
}
