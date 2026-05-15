package com.heikinashi.monitoring.infrastructure.eodhd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heikinashi.monitoring.domain.MarketDataProvider;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.error.TickerNotFoundException;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MarketDataProvider} backed by EODHD's End-of-Day historical-data API
 * ({@code GET /api/eod/{symbol}}). Replaces the abandoned Yahoo Finance scraper:
 * official API, query-param auth, deterministic JSON shape.
 *
 * <p>The {@code symbol} passed in is {@code TICKER.EXCHANGE} where EXCHANGE is
 * EODHD's code (US / LSE / XETRA / SW / MC / MI / TO / PA / AS), built by
 * {@code IngestionConfig.providerSymbol} from the per-deploy suffix map in
 * application.yml.
 */
@Singleton
public class EodhdMarketDataProvider implements MarketDataProvider {

    private static final Logger LOG = LoggerFactory.getLogger(EodhdMarketDataProvider.class);
    private static final String PROVIDER = "eodhd";

    private final EodhdConfig config;
    private final Clock clock;
    private final HttpClient http;
    private final ObjectMapper json;

    public EodhdMarketDataProvider(EodhdConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.json = new ObjectMapper();
    }

    @Override
    public List<OHLCBar> fetchHistory(String symbol, Timeframe tf, Instant since) {
        URI uri = buildUri(symbol, tf, since);
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
        LOG.info("eodhd_call symbol={} status={} bytes={} duration_ms={}", symbol, status, bodyLen, elapsed);

        if (status == 404) {
            throw new TickerNotFoundException(symbol, "");
        }
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

        return parse(symbol, tf, response.body());
    }

    private URI buildUri(String symbol, Timeframe tf, Instant since) {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String from = LocalDate.ofInstant(since, ZoneOffset.UTC).toString();
        String period =
                switch (tf) {
                    case D1 -> "d";
                    case W1 -> "w";
                };
        // api_token via query param per EODHD docs. fmt=json forces structured
        // body; order=a yields ascending dates so downstream code doesn't sort.
        return URI.create(config.getBaseUrl() + "/eod/" + encodedSymbol + "?api_token="
                + URLEncoder.encode(config.getApiKey(), StandardCharsets.UTF_8) + "&fmt=json&period=" + period
                + "&from=" + from + "&order=a");
    }

    private List<OHLCBar> parse(String symbol, Timeframe tf, String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new SchemaDriftException("eodhd.eod", "non-JSON response for " + symbol);
        }
        if (!root.isArray()) {
            throw new SchemaDriftException("eodhd.eod", "expected JSON array, got " + root.getNodeType());
        }
        Instant ingestedAt = clock.instant();
        List<OHLCBar> out = new ArrayList<>(root.size());
        for (JsonNode bar : root) {
            try {
                if (!bar.hasNonNull("date")
                        || !bar.hasNonNull("open")
                        || !bar.hasNonNull("high")
                        || !bar.hasNonNull("low")
                        || !bar.hasNonNull("close")) {
                    LOG.debug("eodhd_skipping_bar symbol={} reason=missing_field", symbol);
                    continue;
                }
                Instant barTime = LocalDate.parse(bar.get("date").asText())
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
                BigDecimal open = new BigDecimal(bar.get("open").asText());
                BigDecimal high = new BigDecimal(bar.get("high").asText());
                BigDecimal low = new BigDecimal(bar.get("low").asText());
                BigDecimal close = new BigDecimal(bar.get("close").asText());
                Optional<BigDecimal> volume = bar.hasNonNull("volume")
                        ? Optional.of(new BigDecimal(bar.get("volume").asText()))
                        : Optional.empty();
                out.add(new OHLCBar("", tf, barTime, open, high, low, close, volume, PROVIDER, ingestedAt));
            } catch (RuntimeException e) {
                throw new SchemaDriftException("eodhd.eod", "could not map bar for " + symbol + ": " + e.getMessage());
            }
        }
        return out;
    }
}
