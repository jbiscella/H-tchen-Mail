package com.heikinashi.monitoring.infrastructure.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.application.InMemoryInstrumentRepository;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AAA unit tests for {@link NewsQueryResolver} (Block 19).
 *
 * <p>The bare ticker is the case that matters: measured against Tavily, {@code AMS.MC} returned
 * AWS landing-zone documentation, Amsterdam flight listings and Perplexity. So a query that is
 * merely the ticker is a defect, not a stylistic choice, and these tests pin that.
 */
class NewsQueryResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");

    private static Instrument instrument(Optional<String> name) {
        return new Instrument("id-1", "AMS", "BME", name, Optional.of("EUR"), InstrumentStatus.ACTIVE, NOW, NOW);
    }

    private static InMemoryInstrumentRepository repoWith(Optional<String> name, Optional<String> override) {
        InMemoryInstrumentRepository repo = new InMemoryInstrumentRepository();
        InstrumentConfig cfg = InstrumentConfig.defaults(NOW).withNewsQuery(override, NOW);
        repo.register(instrument(name), cfg);
        return repo;
    }

    @Test
    void the_derived_query_uses_the_instrument_name_not_the_bare_ticker() {
        NewsQueryResolver resolver =
                new NewsQueryResolver(repoWith(Optional.of("Amadeus IT Group SA"), Optional.empty()));

        String query = resolver.queryFor("AMS", "BME");

        assertThat(query).contains("Amadeus IT Group SA");
        assertThat(query).isNotEqualTo("AMS");
    }

    @Test
    void a_configured_override_wins_verbatim() {
        NewsQueryResolver resolver = new NewsQueryResolver(
                repoWith(Optional.of("Amadeus IT Group SA"), Optional.of("Amadeus IT Group AMS.MC shares")));

        assertThat(resolver.queryFor("AMS", "BME")).isEqualTo("Amadeus IT Group AMS.MC shares");
    }

    @Test
    void an_instrument_without_a_name_degrades_to_the_ticker() {
        // The known-poor query. Acceptable only as a last resort, and logged at WARN so the
        // operator knows to set an override — that log is the mechanism behind "set it when we
        // see the instrument does not work".
        NewsQueryResolver resolver = new NewsQueryResolver(repoWith(Optional.empty(), Optional.empty()));

        assertThat(resolver.queryFor("AMS", "BME")).isEqualTo("AMS");
    }

    @Test
    void an_unknown_instrument_degrades_to_the_ticker() {
        NewsQueryResolver resolver = new NewsQueryResolver(new InMemoryInstrumentRepository());

        assertThat(resolver.queryFor("NVDA", "NASDAQ")).isEqualTo("NVDA");
    }

    @Test
    void a_blank_name_is_treated_as_absent_rather_than_producing_a_leading_space_query() {
        NewsQueryResolver resolver = new NewsQueryResolver(repoWith(Optional.of("   "), Optional.empty()));

        assertThat(resolver.queryFor("AMS", "BME")).isEqualTo("AMS");
    }
}
