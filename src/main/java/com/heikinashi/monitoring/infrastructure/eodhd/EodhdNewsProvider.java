package com.heikinashi.monitoring.infrastructure.eodhd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.news.NewsProvider;
import com.heikinashi.monitoring.infrastructure.news.NewsSymbols;
import com.heikinashi.monitoring.infrastructure.news.RecencyWindowSource;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(EodhdNewsProvider.class);
    private static final String PROVIDER = "eodhd";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ELLIPSIS = "…";

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
        String symbol = NewsSymbols.forExchange(ticker, exchange, suffixMap);
        LocalDate from = recency.publishedAfter(clock, tf);
        LocalDate to = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        URI uri = buildUri(symbol, max, from, to);
        long t0 = System.currentTimeMillis();
        HttpExchange.Result response;
        try {
            response = http.get(uri);
        } catch (IOException e) {
            throw new ProviderUnavailableException(PROVIDER, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException(PROVIDER, e);
        }

        long elapsed = System.currentTimeMillis() - t0;
        int status = response.status();

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

        List<NewsHeadline> headlines = parseNews(response.body(), max, newsConfig.getSummaryMaxChars());
        LOG.info(
                "news_call provider={} symbol={} status={} duration_ms={} count={}",
                PROVIDER,
                symbol,
                status,
                elapsed,
                headlines.size());
        return headlines;
    }

    private URI buildUri(String symbol, int max, LocalDate from, LocalDate to) {
        return URI.create(config.getBaseUrl() + "/news?s="
                + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "&from=" + from + "&to=" + to
                + "&limit=" + Math.max(1, max) + "&api_token="
                + URLEncoder.encode(config.getApiKey(), StandardCharsets.UTF_8));
    }

    /**
     * Parse an EODHD {@code /news} response (a bare JSON array) into headlines.
     * An empty array yields an empty list (no news is not an error); a body
     * that is not an array raises {@link SchemaDriftException}; individual
     * malformed items are skipped. Per the Block 17 mapping: {@code date} →
     * Instant UTC, {@code link} → url verbatim + host-derived source,
     * {@code content} → word-boundary-truncated summary (absent → empty).
     */
    static List<NewsHeadline> parseNews(String body, int max, int summaryMaxChars) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new SchemaDriftException("eodhd.news", "non-JSON response");
        }
        if (root == null || !root.isArray()) {
            throw new SchemaDriftException("eodhd.news", "expected a JSON array of news items");
        }
        List<NewsHeadline> out = new ArrayList<>();
        for (JsonNode article : root) {
            if (out.size() >= max) {
                break;
            }
            if (!article.hasNonNull("title") || !article.hasNonNull("date") || !article.hasNonNull("link")) {
                LOG.debug("eodhd_skipping_article reason=missing_field");
                continue;
            }
            try {
                Instant publishedAt =
                        OffsetDateTime.parse(article.get("date").asText()).toInstant();
                String link = article.get("link").asText();
                String content =
                        article.hasNonNull("content") ? article.get("content").asText() : "";
                out.add(new NewsHeadline(
                        article.get("title").asText(),
                        publishedAt,
                        hostOf(link),
                        link,
                        summarize(content, summaryMaxChars)));
            } catch (RuntimeException e) {
                LOG.debug("eodhd_skipping_article reason=unparsable date");
            }
        }
        return out;
    }

    /** The {@code source} EODHD does not supply: the link's host, or empty on a malformed link — never a failure. */
    static String hostOf(String link) {
        try {
            String host = URI.create(link).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * Truncate a full article body into the summary: at most {@code maxChars}
     * kept, cut back to the last word boundary (the space itself is dropped)
     * with an ellipsis appended; hard cut at {@code maxChars} when no space
     * exists within the limit; verbatim when already short enough.
     */
    static String summarize(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        String head = content.substring(0, maxChars);
        int lastSpace = head.lastIndexOf(' ');
        return (lastSpace > 0 ? head.substring(0, lastSpace) : head) + ELLIPSIS;
    }
}
