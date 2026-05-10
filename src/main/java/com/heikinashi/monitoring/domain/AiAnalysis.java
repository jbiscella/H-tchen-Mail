package com.heikinashi.monitoring.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** AI-generated fundamental analysis attached to an alert (CLAUDE.md §9). */
public record AiAnalysis(
        Optional<String> corroborating,
        Optional<String> contradicting,
        AiConfidence confidence,
        List<String> dataSources) {

    public AiAnalysis {
        Objects.requireNonNull(corroborating, "corroborating");
        Objects.requireNonNull(contradicting, "contradicting");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(dataSources, "dataSources");
        dataSources = List.copyOf(dataSources);
    }
}
