package com.heikinashi.monitoring.infrastructure.tavily;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.news.NewsProvider;
import com.heikinashi.monitoring.infrastructure.news.NewsQueryResolver;
import com.heikinashi.monitoring.infrastructure.news.RecencyWindowSource;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * News-headline adapter backed by Tavily's search API ({@code POST /search} with
 * {@code topic=news}) — Block 19.
 *
 * <p><strong>Why it exists.</strong> The ticker-scoped news APIs do not cover thinly-followed
 * instruments. Measured 2026-08-12: an AAPL control returned 30 same-day EODHD items while
 * {@code AMS.MC}, {@code GAW.LSE} and {@code CFR.SW} returned zero on the same token, endpoint
 * and window. Games Workshop had received one item in 120 days. Meanwhile a domain-constrained
 * web-news search surfaced Amadeus's FY26 guidance downgrade — material, company-specific, and
 * <em>contradicting</em> the bullish signal the pipeline had just reported as unopposed.
 *
 * <p>{@code topic=news} is required rather than optional: only that mode returns
 * {@code published_date}, without which the recency window cannot be applied and stale items
 * would flow straight through the filter Block 18 added.
 *
 * <p>The query comes from {@link NewsQueryResolver}, never from the bare ticker — see that
 * class for the measurement and for why a per-instrument query is not a per-instrument filter.
 */
@Singleton
public class TavilyNewsProvider implements NewsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(TavilyNewsProvider.class);
    private static final String PROVIDER = "tavily";
    private static final String ENDPOINT = "https://api.tavily.com/search";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Tavily caps a single response at 20 regardless of what is asked for. */
    private static final int PROVIDER_MAX_RESULTS = 20;

    /** Tavily's plan-limit codes alongside the standard 429, per its published behaviour. */
    private static final List<Integer> QUOTA_CODES = List.of(429, 432, 433);

    private final TavilyConfig config;
    private final NewsQueryResolver queries;
    private final RecencyWindowSource recency;
    private final HttpClient http;

    /**
     * No {@code Clock} here, unlike the other adapters: Tavily takes a relative {@code days}
     * window rather than an absolute {@code published_after} date, so there is no "now" to
     * inject. The aggregator still applies the absolute window afterwards.
     */
    public TavilyNewsProvider(TavilyConfig config, NewsQueryResolver queries, RecencyWindowSource recency) {
        this.config = config;
        this.queries = queries;
        this.recency = recency;
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
        if (!config.isEnabled()) {
            LOG.debug("news_call provider={} skipped reason=no_api_key", PROVIDER);
            return List.of();
        }
        String query = queries.queryFor(ticker, exchange);
        int days = recency.recencyDays(tf);
        String body = requestBody(query, Math.min(max, PROVIDER_MAX_RESULTS), days);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(URI.create(ENDPOINT))
                            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                            .header("Authorization", "Bearer " + config.getApiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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

        // Quota exhaustion is expected on a free tier and must not fail the run: the aggregator
        // already tolerates one provider returning nothing, and an alert without web news is
        // strictly better than no alert.
        if (QUOTA_CODES.contains(status)) {
            LOG.warn("news_call provider={} status={} quota_exhausted query=\"{}\"", PROVIDER, status, query);
            return List.of();
        }
        if (status == 401 || status == 403) {
            throw new ProviderUnavailableException(
                    PROVIDER, new RuntimeException("auth failed (status=" + status + ")"));
        }
        if (status != 200) {
            throw new ProviderUnavailableException(PROVIDER, new RuntimeException("unexpected status: " + status));
        }

        List<NewsHeadline> headlines = parseNews(response.body(), max);
        // The query AND the titles, per Block 19: the news_query override is meant to be set
        // "when we see the instrument does not work", which is unobservable from a count alone.
        LOG.info(
                "news_call provider={} status={} duration_ms={} count={} days={} query=\"{}\" titles={}",
                PROVIDER,
                status,
                elapsed,
                headlines.size(),
                days,
                query,
                headlines.stream().map(NewsHeadline::title).toList());
        return headlines;
    }

    /** {@code topic=news} is what makes {@code published_date} present; see the class comment. */
    String requestBody(String query, int maxResults, int days) {
        StringBuilder domains = new StringBuilder();
        for (String d : config.getIncludeDomains()) {
            if (!domains.isEmpty()) {
                domains.append(',');
            }
            domains.append('"').append(d).append('"');
        }
        return "{\"query\":%s,\"topic\":\"news\",\"days\":%d,\"max_results\":%d,\"search_depth\":\"basic\",\"include_domains\":[%s]}"
                .formatted(quote(query), days, maxResults, domains);
    }

    private static String quote(String raw) {
        try {
            return JSON.writeValueAsString(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A String always serialises; treat the impossible as a provider fault rather than
            // emitting an unquoted value into a JSON body.
            throw new ProviderUnavailableException(PROVIDER, e);
        }
    }

    /**
     * Tavily returns {@code published_date} as an RFC 1123 timestamp. An item without a parseable
     * date is dropped rather than defaulted: a wrong date would let a months-old item pass the
     * recency window, which is the failure Block 18 Part B removed.
     */
    static List<NewsHeadline> parseNews(String body, int max) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new com.heikinashi.monitoring.domain.error.SchemaDriftException(PROVIDER, "response was not JSON");
        }
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            return List.of();
        }
        List<NewsHeadline> out = new ArrayList<>();
        for (JsonNode item : results) {
            if (out.size() >= max) {
                break;
            }
            String title = text(item, "title");
            String url = text(item, "url");
            String published = text(item, "published_date");
            if (title == null || url == null || published == null) {
                continue;
            }
            try {
                Instant at = ZonedDateTime.parse(published, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .truncatedTo(ChronoUnit.SECONDS);
                String content = text(item, "content");
                // The PUBLISHER, not this adapter's name. ToolCatalog hands NewsHeadline.source()
                // straight to the analyst, and Marketaux passes the article's source while EODHD
                // derives the link host — so a literal "tavily" would have told the model that
                // Reuters and the FT were both published by a search API (Codex P2 on PR #88).
                // It also matters for attribution: the note should credit whoever wrote the story.
                out.add(new NewsHeadline(title, at, publisherOf(url), url, content == null ? "" : content));
            } catch (RuntimeException e) {
                LOG.debug("tavily_skipping_item reason=unparsable_published_date value={}", published);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The publisher, derived from the result URL's host with any leading {@code www.} dropped —
     * mirroring {@code EodhdNewsProvider.hostOf}, which solves the same problem for a provider
     * that supplies no source field. Falls back to the adapter name only when the URL has no
     * parseable host, which is preferable to an empty attribution.
     */
    static String publisherOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) {
                return PROVIDER;
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return PROVIDER;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }
}
