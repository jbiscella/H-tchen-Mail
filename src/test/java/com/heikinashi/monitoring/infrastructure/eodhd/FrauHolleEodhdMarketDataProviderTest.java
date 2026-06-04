package com.heikinashi.monitoring.infrastructure.eodhd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.error.TickerNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.hatrack.commons.Timeframe.Unit;
import org.hatrack.frauholle.error.MarketDataException;
import org.hatrack.frauholle.error.MarketDataNotFoundException;
import org.hatrack.frauholle.error.MarketDataSchemaException;
import org.hatrack.frauholle.error.MarketDataUnavailableException;
import org.hatrack.frauholle.port.MarketDataSource;
import org.junit.jupiter.api.Test;

/**
 * Block 13 — the frau-holle-eodhd adapter behind the MarketDataProvider port:
 * commons bars convert to domain bars, and frau-holle errors map to the domain
 * error catalog. Uses a fake {@link MarketDataSource} (no network).
 */
class FrauHolleEodhdMarketDataProviderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-07T22:00:00Z"), ZoneOffset.UTC);
    private static final Instant SINCE = Instant.parse("2026-05-01T00:00:00Z");

    @Test
    void converts_commons_bars_to_domain_bars_with_eodhd_source_and_empty_instrument_id() {
        Instant t = Instant.parse("2026-05-06T00:00:00Z");
        org.hatrack.commons.OHLCBar commons = new org.hatrack.commons.OHLCBar(
                t,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                Optional.of(new BigDecimal("12345")));
        MarketDataSource fake = (symbol, tf, since, until) -> List.of(commons);

        FrauHolleEodhdMarketDataProvider provider = new FrauHolleEodhdMarketDataProvider(fake, CLOCK);
        List<OHLCBar> bars = provider.fetchHistory("AAPL.US", Timeframe.D1, SINCE);

        assertThat(bars).hasSize(1);
        OHLCBar bar = bars.get(0);
        assertThat(bar.barTime()).isEqualTo(t);
        assertThat(bar.instrumentId()).isEmpty(); // re-stamped by IngestionService
        assertThat(bar.source()).isEqualTo("eodhd");
        assertThat(bar.timeframe()).isEqualTo(Timeframe.D1);
        assertThat(bar.close()).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("105"));
        assertThat(bar.volume()).contains(new BigDecimal("12345"));
        assertThat(bar.ingestedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void passes_the_commons_timeframe_and_a_now_bounded_range_to_the_source() {
        // Capture what the adapter forwards to frau-holle.
        var captured = new Object() {
            String symbol;
            org.hatrack.commons.Timeframe tf;
            Instant since;
            Instant until;
        };
        MarketDataSource fake = (symbol, tf, since, until) -> {
            captured.symbol = symbol;
            captured.tf = tf;
            captured.since = since;
            captured.until = until;
            return List.of();
        };

        new FrauHolleEodhdMarketDataProvider(fake, CLOCK).fetchHistory("BP.LSE", Timeframe.W1, SINCE);

        assertThat(captured.symbol).isEqualTo("BP.LSE");
        assertThat(captured.tf.wire()).isEqualTo("1w");
        assertThat(captured.tf.unit()).isEqualTo(Unit.WEEK);
        assertThat(captured.since).isEqualTo(SINCE);
        assertThat(captured.until).isEqualTo(CLOCK.instant()); // until = now
    }

    @Test
    void maps_not_found_to_ticker_not_found() {
        MarketDataSource fake = (symbol, tf, since, until) -> {
            throw new MarketDataNotFoundException(symbol, "404");
        };
        assertThatThrownBy(() ->
                        new FrauHolleEodhdMarketDataProvider(fake, CLOCK).fetchHistory("NOPE.US", Timeframe.D1, SINCE))
                .isInstanceOf(TickerNotFoundException.class);
    }

    @Test
    void maps_schema_error_to_schema_drift() {
        MarketDataSource fake = (symbol, tf, since, until) -> {
            throw new MarketDataSchemaException(symbol, "bad json");
        };
        assertThatThrownBy(() ->
                        new FrauHolleEodhdMarketDataProvider(fake, CLOCK).fetchHistory("AAPL.US", Timeframe.D1, SINCE))
                .isInstanceOf(SchemaDriftException.class);
    }

    @Test
    void maps_unavailable_to_provider_unavailable() {
        MarketDataSource fake = (symbol, tf, since, until) -> {
            throw new MarketDataUnavailableException(symbol, "429 rate limit");
        };
        assertThatThrownBy(() ->
                        new FrauHolleEodhdMarketDataProvider(fake, CLOCK).fetchHistory("AAPL.US", Timeframe.D1, SINCE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void maps_other_market_data_errors_to_provider_unavailable() {
        MarketDataSource fake = (symbol, tf, since, until) -> {
            throw new MarketDataException(symbol, "weird") {};
        };
        assertThatThrownBy(() ->
                        new FrauHolleEodhdMarketDataProvider(fake, CLOCK).fetchHistory("AAPL.US", Timeframe.D1, SINCE))
                .isInstanceOf(ProviderUnavailableException.class);
    }
}
