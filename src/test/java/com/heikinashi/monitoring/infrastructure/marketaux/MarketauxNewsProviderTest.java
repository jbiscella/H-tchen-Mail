package com.heikinashi.monitoring.infrastructure.marketaux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.news.NewsConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketauxNewsProviderTest {

    /** Block 18 entity-cardinality ceiling; high enough that the committed fixture is unaffected. */
    private static final int MAX_ENTITIES = 6;

    // --- Block 18: query contract + entity cardinality ---

    @Test
    void the_query_requests_entity_resolved_articles_and_omits_filter_entities() {
        MarketauxConfig config = new MarketauxConfig();
        config.setApiKey("test-token");
        MarketauxNewsProvider provider = new MarketauxNewsProvider(config, new NewsConfig(), "{}", Clock.systemUTC());

        String query =
                provider.buildUri("CFR.SW", 30, LocalDate.parse("2026-06-05")).getRawQuery();

        assertThat(query).contains("must_have_entities=true");
        // filter_entities would trim each hit's entity array to the requested symbol,
        // making every item look single-entity and disabling the cardinality filter.
        assertThat(query).doesNotContain("filter_entities");
    }

    @Test
    void an_article_over_the_entity_ceiling_is_dropped() {
        String entities = "[{\"symbol\":\"A\"},{\"symbol\":\"B\"},{\"symbol\":\"C\"},"
                + "{\"symbol\":\"D\"},{\"symbol\":\"E\"},{\"symbol\":\"F\"},{\"symbol\":\"G\"}]"; // 7 > 6
        String body = "{\"data\":[{\"title\":\"round-up\",\"published_at\":\"2026-06-12T10:00:00.000000Z\","
                + "\"source\":\"s\",\"url\":\"https://a/x\",\"entities\":" + entities + "}]}";

        MarketauxNewsProvider.ParseResult out = MarketauxNewsProvider.parseNews(body, 10, MAX_ENTITIES);

        assertThat(out.headlines()).isEmpty();
        assertThat(out.droppedMultiEntity()).isEqualTo(1);
    }

    @Test
    void an_article_with_no_entities_field_is_never_dropped_by_cardinality() {
        String body = "{\"data\":[{\"title\":\"no entities\",\"published_at\":\"2026-06-12T10:00:00.000000Z\","
                + "\"source\":\"s\",\"url\":\"https://a/x\"}]}";

        assertThat(MarketauxNewsProvider.parseNews(body, 10, MAX_ENTITIES).headlines())
                .hasSize(1);
    }

    private static String fixture() {
        try (var in = MarketauxNewsProviderTest.class.getResourceAsStream("/marketaux-cfr-sw.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parses_all_three_articles_from_the_fixture() {
        List<NewsHeadline> news =
                MarketauxNewsProvider.parseNews(fixture(), 10, MAX_ENTITIES).headlines();
        assertThat(news).hasSize(3);
        NewsHeadline first = news.get(0);
        assertThat(first.title()).isEqualTo("Richemont posts record full-year sales on jewellery demand");
        assertThat(first.source()).isEqualTo("reuters.com");
        assertThat(first.url()).isEqualTo("https://example.com/news/richemont-record-sales");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-05-14T06:30:00Z"));
        assertThat(first.summary()).isEqualTo("The Swiss luxury group reported sales above analyst expectations.");
    }

    @Test
    void summary_falls_back_to_snippet_then_empty() {
        String body = "{\"data\":["
                + "{\"title\":\"has snippet\",\"published_at\":\"2026-05-14T06:30:00Z\",\"source\":\"s\","
                + "\"url\":\"https://x.com/a\",\"snippet\":\"snippet text\"},"
                + "{\"title\":\"has neither\",\"published_at\":\"2026-05-14T06:30:00Z\",\"source\":\"s\","
                + "\"url\":\"https://x.com/b\"}]}";
        List<NewsHeadline> news =
                MarketauxNewsProvider.parseNews(body, 10, MAX_ENTITIES).headlines();
        assertThat(news.get(0).summary()).isEqualTo("snippet text");
        assertThat(news.get(1).summary()).isEmpty();
    }

    @Test
    void max_caps_the_number_of_headlines_returned() {
        List<NewsHeadline> news =
                MarketauxNewsProvider.parseNews(fixture(), 2, MAX_ENTITIES).headlines();
        assertThat(news).hasSize(2);
    }

    @Test
    void empty_data_array_yields_an_empty_list() {
        List<NewsHeadline> news = MarketauxNewsProvider.parseNews("{\"data\":[]}", 10, MAX_ENTITIES)
                .headlines();
        assertThat(news).isEmpty();
    }

    @Test
    void article_missing_required_fields_is_skipped_not_fatal() {
        String body = "{\"data\":[{\"title\":\"only a title\"},"
                + "{\"title\":\"no url\",\"published_at\":\"2026-05-14T06:30:00Z\",\"source\":\"x.com\"},"
                + "{\"title\":\"ok\",\"published_at\":\"2026-05-14T06:30:00Z\",\"source\":\"x.com\","
                + "\"url\":\"https://x.com/ok\"}]}";
        List<NewsHeadline> news =
                MarketauxNewsProvider.parseNews(body, 10, MAX_ENTITIES).headlines();
        assertThat(news).hasSize(1);
        assertThat(news.get(0).title()).isEqualTo("ok");
    }

    @Test
    void article_with_unparsable_date_is_skipped() {
        String body = "{\"data\":[{\"title\":\"bad date\",\"published_at\":\"not-a-date\","
                + "\"source\":\"x.com\",\"url\":\"https://x.com/bad\"}]}";
        assertThat(MarketauxNewsProvider.parseNews(body, 10, MAX_ENTITIES).headlines())
                .isEmpty();
    }

    @Test
    void non_json_body_raises_schema_drift() {
        assertThatThrownBy(() -> MarketauxNewsProvider.parseNews("<html>nope</html>", 10, MAX_ENTITIES))
                .isInstanceOf(SchemaDriftException.class);
    }

    @Test
    void missing_data_field_raises_schema_drift() {
        assertThatThrownBy(() -> MarketauxNewsProvider.parseNews("{\"meta\":{}}", 10, MAX_ENTITIES))
                .isInstanceOf(SchemaDriftException.class);
    }
}
