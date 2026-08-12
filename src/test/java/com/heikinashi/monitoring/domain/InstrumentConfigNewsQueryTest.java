package com.heikinashi.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * AAA unit tests for the Block 19 {@code news_query} override on {@link InstrumentConfig}.
 *
 * <p>The withers get their own tests because adding a record component behind a
 * pre-existing convenience constructor sets a trap: that constructor defaults
 * {@code newsQuery} to absent, so any wither which forgot to thread the field would
 * silently erase an operator's override. That is invisible in a compile and would only
 * surface as "my query stopped working after I changed the recipients".
 */
class InstrumentConfigNewsQueryTest {

    private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");

    private static InstrumentConfig withQuery(String query) {
        return InstrumentConfig.defaults(NOW).withNewsQuery(Optional.of(query), NOW);
    }

    @Test
    void defaults_carry_no_override_so_the_derived_query_is_used() {
        assertThat(InstrumentConfig.defaults(NOW).newsQuery()).isEmpty();
    }

    @Test
    void every_wither_preserves_the_override() {
        InstrumentConfig cfg = withQuery("Amadeus IT Group AMS.MC shares");

        assertThat(cfg.withRecipients(Set.of("a@b.com"), NOW).newsQuery()).contains("Amadeus IT Group AMS.MC shares");
        assertThat(cfg.withTrackedTimeframes(Set.of(Timeframe.W1), NOW).newsQuery())
                .contains("Amadeus IT Group AMS.MC shares");
        assertThat(cfg.withPatterns(PatternsConfig.defaults(), NOW).newsQuery())
                .contains("Amadeus IT Group AMS.MC shares");
        assertThat(cfg.withStoragePolicy(StoragePolicy.FULL_HISTORY, Optional.empty(), NOW)
                        .newsQuery())
                .contains("Amadeus IT Group AMS.MC shares");
    }

    @Test
    void a_blank_override_is_treated_as_absent() {
        // An empty query would be sent verbatim and return arbitrary results, which is worse
        // than falling back to the derived one.
        assertThat(withQuery("   ").newsQuery()).isEmpty();
    }

    @Test
    void an_override_is_trimmed() {
        assertThat(withQuery("  Games Workshop GAW.L  ").newsQuery()).contains("Games Workshop GAW.L");
    }

    @Test
    void an_over_long_override_is_rejected() {
        // A query, not a place to grow a filter language.
        String tooLong = "x".repeat(InstrumentConfig.MAX_NEWS_QUERY_LENGTH + 1);

        assertThatThrownBy(() -> withQuery(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newsQuery exceeds");
    }
}
