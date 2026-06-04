package com.heikinashi.monitoring.infrastructure.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.StrategyImportException;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hatrack.dsl.error.IndicatorWarmupException;

/**
 * Imports a strategy from H-tchen's JSON format (Block 15). Each scenario's market
 * conditions are dsl-eval expression STRINGS — H-tchen no longer enumerates a
 * fixed set of condition types, so it supports every primitive dsl-eval knows.
 *
 * <p>Schema (H-tchen artifact):
 *
 * <pre>{@code
 * {
 *   "name": "rsi-reversal-long",
 *   "scenarios": [
 *     {
 *       "name": "oversold-bounce-entry",
 *       "role": "long_entry",                    // display label, never branched on
 *       "positionPrecondition": "flat",          // optional, memo/context only
 *       "stopLoss": "entry * 0.98",              // optional, memo only (verbatim, never evaluated)
 *       "takeProfit": "entry * 1.05",            // optional, memo only (verbatim, never evaluated)
 *       "conditions": [                          // dsl-eval expression strings, ANDed
 *         "rsi(14) crosses below 30",
 *         "ha_bullish_reversal(3)"
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p><b>Fail loud.</b> A condition is rejected and the WHOLE strategy fails to
 * import — never partially monitored — when it:
 * <ul>
 *   <li>references position state ({@code entry_price} / {@code position_size}),
 *       which H-tchen, being stateless, cannot supply — caught statically here at
 *       JSON load, no bars needed;</li>
 *   <li>fails to parse or references a primitive dsl-eval does not support;</li>
 *   <li>needs more history than the instrument has (insufficient history).</li>
 * </ul>
 * The last two require evaluation, so {@link #importFor} validates every condition
 * against the instrument's real persisted OHLC bars.
 */
@Singleton
public class StrategyJsonImporter {

    private static final Pattern POSITION_STATE = Pattern.compile("\\b(entry_price|position_size)\\b");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parse + structural validation + static rejection of position-state
     * references. Does NOT validate parse-correctness / primitive support /
     * history — use {@link #importFor} for that.
     */
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

    /**
     * Full import: parse, then validate every condition by evaluating it against
     * the instrument's real OHLC bars. Rejects the whole strategy (fail loud) on a
     * parse error, an unsupported primitive, or insufficient history, naming the
     * offending condition.
     */
    public Strategy importFor(String json, List<OHLCBar> ohlcSeries, Timeframe tf) {
        Strategy strategy = fromJson(json);
        if (ohlcSeries.isEmpty()) {
            throw new StrategyImportException(strategy.name(), "no OHLC history available to validate the strategy");
        }
        List<OHLCBar> sorted = new ArrayList<>(ohlcSeries);
        sorted.sort(Comparator.comparing(OHLCBar::barTime));

        List<String> allConditions = strategy.scenarios().stream()
                .flatMap(s -> s.conditions().stream())
                .toList();
        DslConditionEvaluator evaluator;
        try {
            evaluator = new DslConditionEvaluator(strategy.name(), sorted, tf, allConditions);
        } catch (RuntimeException e) {
            throw new StrategyImportException(strategy.name(), "could not prepare evaluation: " + e.getMessage());
        }

        int latest = sorted.size() - 1;
        for (StrategyScenario scenario : strategy.scenarios()) {
            for (String condition : scenario.conditions()) {
                try {
                    evaluator.validateCondition(condition, latest);
                } catch (IndicatorWarmupException insufficient) {
                    throw new StrategyImportException(
                            condition, "insufficient history to evaluate this condition: " + insufficient.getMessage());
                } catch (RuntimeException badCondition) {
                    // DslEvaluationException (parse error / unsupported primitive) or
                    // IllegalStateException (unresolved identifier H-tchen can't supply).
                    throw new StrategyImportException(
                            condition,
                            "cannot evaluate (parse error or unsupported primitive): " + badCondition.getMessage());
                }
            }
        }
        return strategy;
    }

    private StrategyScenario parseScenario(JsonNode s) {
        String name = text(s, "name").orElseThrow(() -> new StrategyImportException("<scenario>", "missing 'name'"));
        String role = text(s, "role").orElseThrow(() -> new StrategyImportException(name, "scenario missing 'role'"));
        JsonNode conditionsNode = s.get("conditions");
        if (conditionsNode == null || !conditionsNode.isArray() || conditionsNode.isEmpty()) {
            throw new StrategyImportException(name, "scenario must declare a non-empty 'conditions' array");
        }
        List<String> conditions = new ArrayList<>();
        for (JsonNode c : conditionsNode) {
            if (!c.isTextual() || c.asText().isBlank()) {
                throw new StrategyImportException(name, "each condition must be a non-empty expression string");
            }
            String expression = c.asText();
            if (POSITION_STATE.matcher(expression).find()) {
                throw new StrategyImportException(
                        expression,
                        "references position state (entry_price / position_size) that H-tchen, being "
                                + "stateless, cannot supply — move it to stopLoss/takeProfit memo text instead");
            }
            conditions.add(expression);
        }
        return new StrategyScenario(
                name, role, conditions, text(s, "positionPrecondition"), text(s, "stopLoss"), text(s, "takeProfit"));
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? Optional.of(v.asText()) : Optional.empty();
    }
}
