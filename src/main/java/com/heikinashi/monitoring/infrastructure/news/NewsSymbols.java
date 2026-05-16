package com.heikinashi.monitoring.infrastructure.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/**
 * Builds the provider symbol news adapters query with.
 *
 * <p>News providers (Marketaux, Yahoo Finance RSS) use the <em>common</em>
 * market-suffix convention — {@code .SW} for SIX Swiss, {@code .L} for London,
 * {@code .DE} for Xetra, no suffix for US listings — which is <em>different</em>
 * from EODHD's history convention ({@code .US} / {@code .LSE} / {@code .XETRA}).
 * Passing the internal exchange code ({@code SWX}, {@code LSE}, ...) straight
 * through produces an unresolvable symbol like {@code CFR.SWX} and the provider
 * silently falls back to fuzzy, sector-wide, stale results — so the exchange
 * must always be mapped through {@code monitoring.exchanges.news-suffix-map}.
 */
public final class NewsSymbols {

    private static final ObjectMapper JSON = new ObjectMapper();

    private NewsSymbols() {}

    /** Parse the {@code news-suffix-map} JSON string into an exchange → suffix map. */
    public static Map<String, String> parseSuffixMap(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = JSON.readValue(json, Map.class);
            return Map.copyOf(parsed);
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse news-suffix-map JSON: " + json, e);
        }
    }

    /**
     * The symbol to query a news provider with: {@code ticker} plus the
     * exchange's news suffix (e.g. {@code CFR} + {@code .SW} → {@code CFR.SW};
     * {@code AAPL} + {@code ""} → {@code AAPL}). An exchange absent from the
     * map yields the bare ticker.
     */
    public static String forExchange(String ticker, String exchange, Map<String, String> suffixMap) {
        return ticker + suffixMap.getOrDefault(exchange, "");
    }
}
