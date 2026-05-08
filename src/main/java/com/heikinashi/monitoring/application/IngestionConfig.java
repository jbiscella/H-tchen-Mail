package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.Timeframe;
import java.util.Map;
import java.util.Objects;

/** Non-domain ingestion knobs (CLAUDE.md §6 + §14). */
public record IngestionConfig(
        int circuitBreakerThreshold,
        double failureRateAlertThreshold,
        Map<Timeframe, Integer> bootstrapSizes,
        Map<String, String> exchangeSuffixMap) {

    public IngestionConfig {
        Objects.requireNonNull(bootstrapSizes, "bootstrapSizes");
        Objects.requireNonNull(exchangeSuffixMap, "exchangeSuffixMap");
        bootstrapSizes = Map.copyOf(bootstrapSizes);
        exchangeSuffixMap = Map.copyOf(exchangeSuffixMap);
    }

    public int bootstrapSize(Timeframe tf) {
        Integer v = bootstrapSizes.get(tf);
        return v == null ? 0 : v;
    }

    public String yahooSymbol(String ticker, String exchange) {
        String suffix = exchangeSuffixMap.getOrDefault(exchange, "");
        return ticker + suffix;
    }
}
