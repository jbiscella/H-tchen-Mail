package com.heikinashi.monitoring.infrastructure.bedrock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiConfidence;
import com.heikinashi.monitoring.domain.error.LLMException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Parses the AI analyst's final JSON output into an {@link AiAnalysis}
 * record. The required schema (CLAUDE.md §9):
 *
 * <pre>{@code
 * {
 *   "corroborating": "string, optional",
 *   "contradicting": "string, optional",
 *   "confidence":    "LOW | MEDIUM | HIGH",
 *   "data_sources":  ["news_headlines(5)", "recommendations(0)", ...]
 * }
 * }</pre>
 *
 * <p>Tolerates surrounding whitespace and trailing tokens but expects a single
 * JSON object as the entire output. Anything else surfaces as
 * {@link LLMException}.
 */
final class AiAnalysisJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiAnalysisJson() {}

    @SuppressWarnings("unchecked")
    static AiAnalysis parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LLMException("empty response from AI analyst");
        }
        String trimmed = extractJsonObject(raw);
        Map<String, Object> map;
        try {
            map = MAPPER.readValue(trimmed, Map.class);
        } catch (JsonProcessingException e) {
            throw new LLMException("AI analyst returned non-JSON output", e);
        }

        Object confidenceRaw = map.get("confidence");
        if (confidenceRaw == null) {
            throw new LLMException("AI analyst output missing required field: confidence");
        }
        AiConfidence confidence;
        try {
            confidence = AiConfidence.fromWire(confidenceRaw.toString());
        } catch (IllegalArgumentException e) {
            throw new LLMException("AI analyst returned unknown confidence: " + confidenceRaw, e);
        }

        Optional<String> corroborating = stringField(map, "corroborating");
        Optional<String> contradicting = stringField(map, "contradicting");

        List<String> dataSources = new ArrayList<>();
        Object ds = map.get("data_sources");
        if (ds instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    dataSources.add(item.toString());
                }
            }
        }
        return new AiAnalysis(corroborating, contradicting, confidence, dataSources);
    }

    /** Strip optional surrounding whitespace and tolerate a trailing comma or chatter. */
    private static String extractJsonObject(String raw) {
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LLMException("AI analyst output does not contain a JSON object");
        }
        return s.substring(start, end + 1);
    }

    private static Optional<String> stringField(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Optional.empty();
        }
        String s = flatten(v).trim();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    /**
     * Render a field value as prose. The prompt requires a plain string, but a
     * model may still answer with an array or object; flattening collects the
     * textual content into readable text rather than leaking Java's
     * {@code List}/{@code Map} {@code toString()} (e.g. {@code [{headline=...}]})
     * verbatim into the alert email.
     */
    private static String flatten(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Map<?, ?> m) {
            return m.values().stream()
                    .map(AiAnalysisJson::flatten)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining(" — "));
        }
        if (v instanceof List<?> list) {
            return list.stream()
                    .map(AiAnalysisJson::flatten)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining("\n\n"));
        }
        return String.valueOf(v);
    }
}
