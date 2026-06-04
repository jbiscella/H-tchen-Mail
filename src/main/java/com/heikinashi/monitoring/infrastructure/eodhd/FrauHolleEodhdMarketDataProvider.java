package com.heikinashi.monitoring.infrastructure.eodhd;

import com.heikinashi.monitoring.domain.MarketDataProvider;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.error.TickerNotFoundException;
import com.heikinashi.monitoring.infrastructure.hatrack.CommonsBarAdapter;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hatrack.frauholle.eodhd.EodhdMarketDataSource;
import org.hatrack.frauholle.eodhd.internal.DefaultJsonReader;
import org.hatrack.frauholle.eodhd.internal.JdkHttpExecutor;
import org.hatrack.frauholle.error.MarketDataException;
import org.hatrack.frauholle.error.MarketDataNotFoundException;
import org.hatrack.frauholle.error.MarketDataSchemaException;
import org.hatrack.frauholle.error.MarketDataUnavailableException;
import org.hatrack.frauholle.port.MarketDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block 13 — {@link MarketDataProvider} backed by ha-track's
 * {@code frau-holle-eodhd} ({@code org.hatrack.frauholle.eodhd.EodhdMarketDataSource})
 * instead of the custom EODHD HTTP client, so the EODHD integration is shared
 * and maintained in one place.
 *
 * <p>The {@code MarketDataProvider} port is unchanged; only the implementation
 * swaps. frau-holle returns commons {@code OHLCBar}s (closed, ascending, unique)
 * which are converted to domain bars via {@link CommonsBarAdapter}. As with the
 * previous adapter, the returned bars carry an empty {@code instrumentId} —
 * {@code IngestionService} re-stamps it on persist — plus {@code source="eodhd"}
 * and {@code ingestedAt} from the clock.
 *
 * <p>API-key injection is unchanged: the token comes from {@link EodhdConfig}
 * (env {@code MONITORING_EODHD_API_KEY}). frau-holle builds its URL as
 * {@code <base>/api/eod/<symbol>}, so the configured base ("https://eodhd.com/api")
 * has its trailing "/api" stripped to avoid a doubled path segment.
 */
@Singleton
public class FrauHolleEodhdMarketDataProvider implements MarketDataProvider {

    private static final Logger LOG = LoggerFactory.getLogger(FrauHolleEodhdMarketDataProvider.class);
    private static final String PROVIDER = "eodhd";

    private final Clock clock;
    private final MarketDataSource source;

    public FrauHolleEodhdMarketDataProvider(EodhdConfig config, Clock clock) {
        this(buildSource(config), clock);
    }

    /** Test seam: inject a {@link MarketDataSource} fake without touching the network. */
    FrauHolleEodhdMarketDataProvider(MarketDataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    private static MarketDataSource buildSource(EodhdConfig config) {
        Duration timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        return new EodhdMarketDataSource(
                config.getApiKey(),
                baseUrlFor(config.getBaseUrl()),
                timeout,
                new JdkHttpExecutor(timeout),
                new DefaultJsonReader());
    }

    @Override
    public List<OHLCBar> fetchHistory(String symbol, Timeframe tf, Instant since) {
        Instant until = clock.instant();
        Instant ingestedAt = clock.instant();
        try {
            List<org.hatrack.commons.OHLCBar> bars =
                    source.fetchHistory(symbol, CommonsBarAdapter.toCommons(tf), since, until);
            LOG.info("eodhd_call symbol={} bars={}", symbol, bars.size());
            // instrumentId is re-stamped by IngestionService on persist.
            return bars.stream()
                    .map(b -> CommonsBarAdapter.fromCommons(b, "", tf, PROVIDER, ingestedAt))
                    .toList();
        } catch (MarketDataNotFoundException e) {
            throw new TickerNotFoundException(symbol, "");
        } catch (MarketDataSchemaException e) {
            throw new SchemaDriftException("eodhd.eod", e.getMessage());
        } catch (MarketDataUnavailableException e) {
            throw new ProviderUnavailableException(PROVIDER, e);
        } catch (MarketDataException e) {
            throw new ProviderUnavailableException(PROVIDER, e);
        }
    }

    /** frau-holle appends {@code /api/eod/...}; strip a trailing {@code /api} (and slash) from the config base. */
    private static String baseUrlFor(String configuredBase) {
        String base = configuredBase;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/api")) {
            base = base.substring(0, base.length() - "/api".length());
        }
        return base;
    }
}
