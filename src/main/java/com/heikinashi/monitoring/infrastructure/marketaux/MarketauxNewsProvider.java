package com.heikinashi.monitoring.infrastructure.marketaux;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.news.NewsConfig;
import com.heikinashi.monitoring.infrastructure.news.NewsProvider;
import com.heikinashi.monitoring.infrastructure.news.NewsSymbols;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * News-headline adapter backed by the Marketaux API
 * ({@code GET /v1/news/all}). Standalone — not a {@link
 * com.heikinashi.monitoring.domain.MarketDataProvider}; it is composed into
 * {@link com.heikinashi.monitoring.infrastructure.CompositeMarketDataProvider}
 * alongside the EODHD history adapter.
 *
 * <p>The symbol is built via {@link NewsSymbols} from
 * {@code monitoring.exchanges.news-suffix-map} — Marketaux wants the common
 * market suffix ({@code CFR.SW}), not the internal exchange code
 * ({@code CFR.SWX}), which it cannot resolve.
 *
 * <p>Without a {@code published_after} filter Marketaux ranks {@code /news/all}
 * by relevance, surfacing high-entity-match articles that can be months stale.
 * The adapter therefore always sends {@code published_after}, set to the
 * pattern timeframe's recency window (see {@link MarketauxConfig}), so only
 * genuinely recent headlines come back.
 */
@Singleton
public class MarketauxNewsProvider implements NewsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MarketauxNewsProvider.class);
    private static final String PROVIDER = "marketaux";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MarketauxConfig config;
    private final NewsConfig sharedNewsConfig;
    private final Map<String, String> suffixMap;
    private final Clock clock;
    private final HttpClient http;

    public MarketauxNewsProvider(
            MarketauxConfig config,
            NewsConfig sharedNewsConfig,
            @Value("${monitoring.exchanges.news-suffix-map:{}}") String newsSuffixMapJson,
            Clock clock) {
        this.config = config;
        this.sharedNewsConfig = sharedNewsConfig;
        this.suffixMap = NewsSymbols.parseSuffixMap(newsSuffixMapJson);
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return PROVIDER;
    }

    @Override
    public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max, Timeframe tf) {
        String symbol = NewsSymbols.forExchange(ticker, exchange, suffixMap);
        LocalDate publishedAfter = config.publishedAfter(clock, tf);
        URI uri = buildUri(symbol, max, publishedAfter);
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

        int maxEntities = sharedNewsConfig.getMaxEntitiesPerItem();
        ParseResult parsed = parseNews(response.body(), max, maxEntities);
        LOG.info(
                "news_call provider={} symbol={} status={} duration_ms={} count={} dropped_multi_entity={} max_entities={}",
                PROVIDER,
                symbol,
                status,
                elapsed,
                parsed.headlines().size(),
                parsed.droppedMultiEntity(),
                maxEntities);
        return parsed.headlines();
    }

    /**
     * Block 18 step 1: {@code must_have_entities=true} makes Marketaux return only
     * articles where the requested symbol is an identified <em>entity</em>, rather than
     * anything its tagging loosely associates — pushing relevance resolution to the
     * provider, which tracks entities across its whole source set.
     *
     * <p>{@code filter_entities} is deliberately NOT sent. It trims each hit's returned
     * entity array down to the requested symbol, which would make every item look
     * single-entity and silently disable the entity-cardinality filter in
     * {@link #parseNews}. Leaving it off keeps the full array available to count.
     *
     * <p>Package-private so the query contract can be asserted directly; this provider
     * builds its {@link HttpClient} internally and has no injectable exchange seam, and
     * adding one is more than this assertion needs.
     */
    URI buildUri(String symbol, int max, LocalDate publishedAfter) {
        return URI.create(config.getBaseUrl() + "/news/all?must_have_entities=true&symbols="
                + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "&limit=" + Math.max(1, max)
                + "&published_after=" + publishedAfter + "&api_token="
                + URLEncoder.encode(config.getApiKey(), StandardCharsets.UTF_8));
    }

    /**
     * Parse a Marketaux {@code /news/all} response into headlines. A 200 with an
     * empty {@code data} array yields an empty list (no news today is not an
     * error); a body that is not the expected object/array shape raises
     * {@link SchemaDriftException}; individual malformed articles are skipped.
     */
    static ParseResult parseNews(String body, int max, int maxEntities) {
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
        int droppedMultiEntity = 0;
        for (JsonNode article : data) {
            if (out.size() >= max) {
                break;
            }
            if (!article.hasNonNull("title")
                    || !article.hasNonNull("published_at")
                    || !article.hasNonNull("source")
                    || !article.hasNonNull("url")) {
                LOG.debug("marketaux_skipping_article reason=missing_field");
                continue;
            }
            // Block 18 step 3: drop multi-company digests on entity cardinality alone.
            if (entityCount(article) > maxEntities) {
                droppedMultiEntity++;
                LOG.debug("marketaux_skipping_article reason=multi_entity_digest entities={}", entityCount(article));
                continue;
            }
            try {
                Instant publishedAt = Instant.parse(article.get("published_at").asText());
                out.add(new NewsHeadline(
                        article.get("title").asText(),
                        publishedAt,
                        article.get("source").asText(),
                        article.get("url").asText(),
                        summaryOf(article)));
            } catch (RuntimeException e) {
                LOG.debug("marketaux_skipping_article reason=unparsable published_at");
            }
        }
        return new ParseResult(out, droppedMultiEntity);
    }

    /**
     * Parsed headlines plus how many items the entity-cardinality rule dropped, logged
     * so {@code max-entities-per-item} can be tuned on evidence rather than guessed.
     */
    record ParseResult(List<NewsHeadline> headlines, int droppedMultiEntity) {}

    /**
     * How many entities Marketaux linked to an item. An absent or non-array
     * {@code entities} field counts as 0, so an item with no entity list is never
     * dropped by the cardinality rule — the signal is optional by design.
     */
    static int entityCount(JsonNode article) {
        JsonNode entities = article.get("entities");
        return entities != null && entities.isArray() ? entities.size() : 0;
    }

    /** Marketaux's per-article blurb: prefer {@code description}, fall back to {@code snippet}, else empty. */
    private static String summaryOf(JsonNode article) {
        if (article.hasNonNull("description")) {
            return article.get("description").asText();
        }
        if (article.hasNonNull("snippet")) {
            return article.get("snippet").asText();
        }
        return "";
    }
}
