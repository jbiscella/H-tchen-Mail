package com.heikinashi.monitoring.infrastructure.tavily;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.application.InMemoryInstrumentRepository;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.news.NewsQueryResolver;
import com.heikinashi.monitoring.infrastructure.news.RecencyWindowSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AAA unit tests for {@link TavilyNewsProvider} (Block 19). Follows the Marketaux/EODHD
 * precedent of testing the pure seams — request body and response parsing — rather than mocking
 * the transport.
 */
class TavilyNewsProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");
    private static final RecencyWindowSource RECENCY = tf -> tf == Timeframe.W1 ? 30 : 7;

    private static TavilyNewsProvider provider(TavilyConfig config) {
        InMemoryInstrumentRepository repo = new InMemoryInstrumentRepository();
        repo.register(
                new Instrument(
                        "id-1",
                        "AMS",
                        "BME",
                        Optional.of("Amadeus IT Group SA"),
                        Optional.of("EUR"),
                        InstrumentStatus.ACTIVE,
                        NOW,
                        NOW),
                InstrumentConfig.defaults(NOW));
        return new TavilyNewsProvider(config, new NewsQueryResolver(repo), RECENCY);
    }

    private static TavilyConfig config() {
        TavilyConfig c = new TavilyConfig();
        c.setApiKey("test-key");
        c.setIncludeDomains(List.of("reuters.com", "ft.com"));
        return c;
    }

    @Test
    void the_request_asks_for_the_news_topic_and_the_configured_domains() {
        // topic=news is not cosmetic: it is the only mode that returns published_date, without
        // which the recency window cannot be applied at all.
        String body = provider(config()).requestBody("Amadeus IT Group SA AMS shares", 5, 7);

        assertThat(body).contains("\"topic\":\"news\"");
        assertThat(body).contains("\"days\":7");
        assertThat(body).contains("\"max_results\":5");
        assertThat(body).contains("\"include_domains\":[\"reuters.com\",\"ft.com\"]");
        assertThat(body).contains("\"query\":\"Amadeus IT Group SA AMS shares\"");
    }

    @Test
    void a_query_containing_quotes_is_json_escaped_rather_than_breaking_the_body() {
        // An operator override is free text; unescaped it would produce invalid JSON and a 4xx.
        String body = provider(config()).requestBody("Amadeus \"IT\" Group", 5, 7);

        assertThat(body).contains("\\\"IT\\\"");
        // Still one well-formed JSON document.
        assertThat(body).startsWith("{").endsWith("}");
    }

    @Test
    void published_dates_are_parsed_so_the_recency_window_can_apply() {
        String json = """
                {"results":[{"title":"Amadeus Trims FY26 Outlook","url":"https://reuters.com/a",
                "content":"guidance cut","published_date":"Wed, 05 Aug 2026 11:00:00 GMT"}]}""";

        List<NewsHeadline> out = TavilyNewsProvider.parseNews(json, 5);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).publishedAt()).isEqualTo(Instant.parse("2026-08-05T11:00:00Z"));
        assertThat(out.get(0).title()).isEqualTo("Amadeus Trims FY26 Outlook");
    }

    @Test
    void an_item_with_an_unparseable_date_is_dropped_not_defaulted() {
        // Defaulting would let an undated item pass the recency window, which is precisely the
        // stale-news failure Block 18 Part B removed.
        String json = """
                {"results":[{"title":"No date","url":"https://ft.com/x","published_date":"yesterday"},
                {"title":"Good","url":"https://ft.com/y","published_date":"Wed, 05 Aug 2026 11:00:00 GMT"}]}""";

        List<NewsHeadline> out = TavilyNewsProvider.parseNews(json, 5);

        assertThat(out).extracting(NewsHeadline::title).containsExactly("Good");
    }

    @Test
    void an_item_missing_a_title_or_url_is_skipped() {
        String json = """
                {"results":[{"url":"https://ft.com/x","published_date":"Wed, 05 Aug 2026 11:00:00 GMT"},
                {"title":"No url","published_date":"Wed, 05 Aug 2026 11:00:00 GMT"}]}""";

        assertThat(TavilyNewsProvider.parseNews(json, 5)).isEmpty();
    }

    @Test
    void a_response_without_results_yields_no_headlines() {
        assertThat(TavilyNewsProvider.parseNews("{\"answer\":\"none\"}", 5)).isEmpty();
    }

    @Test
    void the_max_is_honoured() {
        String item = "{\"title\":\"t%d\",\"url\":\"https://ft.com/%d\","
                + "\"published_date\":\"Wed, 05 Aug 2026 11:00:00 GMT\"}";
        String json = "{\"results\":[" + item.formatted(1, 1) + "," + item.formatted(2, 2) + "," + item.formatted(3, 3)
                + "]}";

        assertThat(TavilyNewsProvider.parseNews(json, 2)).hasSize(2);
    }

    @Test
    void no_api_key_disables_the_provider_instead_of_failing_the_run() {
        // A fork with no Tavily key must still produce alerts.
        TavilyConfig noKey = new TavilyConfig();

        assertThat(noKey.isEnabled()).isFalse();
        assertThat(provider(noKey).fetchNewsHeadlines("AMS", "BME", 5, Timeframe.D1))
                .isEmpty();
    }

    @Test
    void the_weekly_timeframe_widens_the_days_window() {
        // The window comes from the shared RecencyWindowSource, so Tavily cannot drift from the
        // window the other providers and the aggregator use.
        assertThat(RECENCY.recencyDays(Timeframe.W1)).isEqualTo(30);
        assertThat(provider(config()).requestBody("q", 5, RECENCY.recencyDays(Timeframe.W1)))
                .contains("\"days\":30");
    }
}
