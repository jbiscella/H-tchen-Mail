package com.heikinashi.monitoring.infrastructure.marketaux;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * News-headline adapter backed by the Marketaux API
 * ({@code GET /v1/news/all}). Standalone — not a {@link
 * com.heikinashi.monitoring.domain.MarketDataProvider}; it is composed into
 * {@link com.heikinashi.monitoring.infrastructure.CompositeMarketDataProvider}
 * alongside the EODHD history adapter.
 *
 * <p>The symbol passed to Marketaux is {@code TICKER.EXCHANGE} — Marketaux's
 * entity matcher accepts that form for both US and non-US listings.
 */
@Singleton
public class MarketauxNewsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MarketauxNewsProvider.class);
    private static final String PROVIDER = "marketaux";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MarketauxConfig config;
    private final HttpClient http;

    public MarketauxNewsProvider(MarketauxConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max) {
        String symbol = ticker + "." + exchange;
        URI uri = buildUri(symbol, max);
        long t0 = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderUnavailableException(PROVIDER, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException(PROVIDER, e);
        }

        long elapsed = System.currentTimeMillis() - t0;
        int status = response.statusCode();
        int bodyLen = response.body() == null ? 0 : response.body().length();
        LOG.info("marketaux_call symbol={} status={} bytes={} duration_ms={}", symbol, status, bodyLen, elapsed);

        if (status == 401 || status == 403) {
            throw new ProviderUnavailableException(
                    PROVIDER, new RuntimeException("auth failed (status=" + status + ")"));
        }
        if (status == 429) {
            throw new ProviderUnavailableException(PROVIDER, new RuntimeException("rate limit / quota exhausted"));
        }
        if (status >= 500 && status < 600) {
            throw new ProviderUnavailableException(PROVIDER, new RuntimeException("upstream 5xx: " + status));
        }
        if (status != 200) {
            throw new ProviderUnavailableException(PROVIDER, new RuntimeException("unexpected status: " + status));
        }

        return parseNews(response.body(), max);
    }

    private URI buildUri(String symbol, int max) {
        return URI.create(config.getBaseUrl() + "/news/all?symbols="
                + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "&limit=" + Math.max(1, max) + "&api_token="
                + URLEncoder.encode(config.getApiKey(), StandardCharsets.UTF_8));
    }

    /**
     * Parse a Marketaux {@code /news/all} response into headlines. A 200 with an
     * empty {@code data} array yields an empty list (no news today is not an
     * error); a body that is not the expected object/array shape raises
     * {@link SchemaDriftException}; individual malformed articles are skipped.
     */
    static List<NewsHeadline> parseNews(String body, int max) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new SchemaDriftException("marketaux.news", "non-JSON response");
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new SchemaDriftException("marketaux.news", "missing or non-array 'data' field");
        }
        List<NewsHeadline> out = new ArrayList<>();
        for (JsonNode article : data) {
            if (out.size() >= max) {
                break;
            }
            if (!article.hasNonNull("title") || !article.hasNonNull("published_at") || !article.hasNonNull("source")) {
                LOG.debug("marketaux_skipping_article reason=missing_field");
                continue;
            }
            try {
                Instant publishedAt = Instant.parse(article.get("published_at").asText());
                out.add(new NewsHeadline(
                        article.get("title").asText(),
                        publishedAt,
                        article.get("source").asText()));
            } catch (RuntimeException e) {
                LOG.debug("marketaux_skipping_article reason=unparsable published_at");
            }
        }
        return out;
    }
}
