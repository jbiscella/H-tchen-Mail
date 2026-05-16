package com.heikinashi.monitoring.infrastructure.yahoo;

import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.news.NewsProvider;
import com.heikinashi.monitoring.infrastructure.news.NewsSymbols;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * {@link NewsProvider} backed by the Yahoo Finance headline RSS feed
 * ({@code GET feeds.finance.yahoo.com/rss/2.0/headline?s=SYMBOL}). No API key;
 * a browser-like {@code User-Agent} is mandatory or Yahoo answers 429.
 *
 * <p>Yahoo's ticker suffixes differ from EODHD's (".L" not ".LSE", ".DE" not
 * ".XETRA", no suffix for US), so the symbol is built via {@link NewsSymbols}
 * from {@code monitoring.exchanges.news-suffix-map}. The feed carries no
 * per-item source or sentiment, so {@code source} is reported as
 * "Yahoo Finance".
 */
@Singleton
public class YahooFinanceRssNewsProvider implements NewsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(YahooFinanceRssNewsProvider.class);
    private static final String PROVIDER = "yahoo-rss";
    private static final String SOURCE = "Yahoo Finance";
    private static final String FEED_URL = "https://feeds.finance.yahoo.com/rss/2.0/headline";
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final int TIMEOUT_SECONDS = 10;

    private final Map<String, String> suffixMap;
    private final HttpClient http;

    public YahooFinanceRssNewsProvider(@Value("${monitoring.exchanges.news-suffix-map:{}}") String newsSuffixMapJson) {
        this.suffixMap = NewsSymbols.parseSuffixMap(newsSuffixMapJson);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return PROVIDER;
    }

    @Override
    public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max, Timeframe tf) {
        // tf is unused: the Yahoo RSS headline feed has no date-range filter —
        // it simply returns the latest headlines.
        String symbol = NewsSymbols.forExchange(ticker, exchange, suffixMap);
        URI uri = URI.create(
                FEED_URL + "?s=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "&region=US&lang=en-US");
        long t0 = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/rss+xml, application/xml")
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
        if (status == 429) {
            throw new ProviderUnavailableException(PROVIDER, new RuntimeException("rate limit (missing User-Agent?)"));
        }
        if (status == 401 || status == 403) {
            throw new ProviderUnavailableException(
                    PROVIDER, new RuntimeException("auth failed (status=" + status + ")"));
        }
        if (status >= 500 && status < 600) {
            throw new ProviderUnavailableException(PROVIDER, new RuntimeException("upstream 5xx: " + status));
        }
        if (status != 200) {
            throw new ProviderUnavailableException(PROVIDER, new RuntimeException("unexpected status: " + status));
        }

        List<NewsHeadline> headlines = parseRss(response.body(), max);
        LOG.info(
                "news_call provider={} symbol={} status={} duration_ms={} count={}",
                PROVIDER,
                symbol,
                status,
                elapsed,
                headlines.size());
        return headlines;
    }

    /**
     * Parse an RSS 2.0 feed into headlines. Items missing a title, link, or
     * parseable {@code pubDate} are skipped; a body that is not XML raises
     * {@link SchemaDriftException}.
     */
    static List<NewsHeadline> parseRss(String xml, int max) {
        org.w3c.dom.Document doc;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // RSS feeds carry no DTD; disallowing one blocks XXE.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new SchemaDriftException("yahoo.rss", "could not parse RSS XML");
        }
        NodeList items = doc.getElementsByTagName("item");
        List<NewsHeadline> out = new ArrayList<>();
        for (int i = 0; i < items.getLength() && out.size() < max; i++) {
            if (!(items.item(i) instanceof Element item)) {
                continue;
            }
            String title = childText(item, "title");
            String link = childText(item, "link");
            String pubDate = childText(item, "pubDate");
            if (title == null || link == null || pubDate == null) {
                LOG.debug("yahoo_rss_skipping_item reason=missing_field");
                continue;
            }
            try {
                Instant publishedAt = ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                out.add(new NewsHeadline(title, publishedAt, SOURCE, link));
            } catch (RuntimeException e) {
                LOG.debug("yahoo_rss_skipping_item reason=unparsable_pubDate");
            }
        }
        return out;
    }

    private static String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            // getElementsByTagName is recursive — keep only direct children.
            if (n.getParentNode() == parent) {
                String text = n.getTextContent();
                return text == null || text.isBlank() ? null : text.trim();
            }
        }
        return null;
    }
}
