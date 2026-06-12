package com.heikinashi.monitoring.infrastructure.eodhd;

import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.news.NewsProvider;
import com.heikinashi.monitoring.infrastructure.news.NewsSymbols;
import com.heikinashi.monitoring.infrastructure.news.RecencyWindowSource;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Block 17 — news-headline adapter backed by the EODHD News API
 * ({@code GET /api/news?s=...&from=...&to=...}). Third {@link NewsProvider}
 * beside Marketaux and Yahoo RSS.
 *
 * <p>Reuses the OHLC ingestion plumbing on purpose: the token and base URL
 * come from {@link EodhdConfig} (same credential), and the symbol is built
 * from {@code monitoring.exchanges.suffix-map} — the EODHD codes
 * ({@code ENI} + {@code .MI} → {@code ENI.MI}), NOT the common-market
 * {@code news-suffix-map} the other two news adapters use. The recency
 * window is derived through {@link RecencyWindowSource} — the same
 * tf → look-back derivation the Marketaux adapter applies.
 */
@Singleton
public class EodhdNewsProvider implements NewsProvider {

    private static final String PROVIDER = "eodhd";

    /**
     * The single HTTP exchange, isolated so tests can script responses and
     * record the queried URI without a network (the same seam idea as
     * {@link FrauHolleEodhdMarketDataProvider}'s injectable source).
     */
    @FunctionalInterface
    public interface HttpExchange {
        Result get(URI uri) throws IOException, InterruptedException;

        record Result(int status, String body) {}
    }

    private final EodhdConfig config;
    private final EodhdNewsConfig newsConfig;
    private final Map<String, String> suffixMap;
    private final RecencyWindowSource recency;
    private final Clock clock;
    private final HttpExchange http;

    public EodhdNewsProvider(
            EodhdConfig config,
            EodhdNewsConfig newsConfig,
            @Value("${monitoring.exchanges.suffix-map:{}}") String suffixMapJson,
            RecencyWindowSource recency,
            Clock clock) {
        this(config, newsConfig, NewsSymbols.parseSuffixMap(suffixMapJson), recency, clock, jdkHttp(config));
    }

    /** Test seam: script the HTTP exchange without touching the network. */
    public EodhdNewsProvider(
            EodhdConfig config,
            EodhdNewsConfig newsConfig,
            Map<String, String> suffixMap,
            RecencyWindowSource recency,
            Clock clock,
            HttpExchange http) {
        this.config = config;
        this.newsConfig = newsConfig;
        this.suffixMap = Map.copyOf(suffixMap);
        this.recency = recency;
        this.clock = clock;
        this.http = http;
    }

    private static HttpExchange jdkHttp(EodhdConfig config) {
        Duration timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return uri -> {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(timeout)
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpExchange.Result(response.statusCode(), response.body());
        };
    }

    @Override
    public String name() {
        return PROVIDER;
    }

    @Override
    public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max, Timeframe tf) {
        throw new UnsupportedOperationException("Block 17 green phase not implemented yet");
    }
}
