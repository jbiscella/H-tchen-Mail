package com.heikinashi.monitoring.infrastructure.marketaux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketauxNewsProviderTest {

    private static String fixture() {
        try (var in = MarketauxNewsProviderTest.class.getResourceAsStream("/marketaux-cfr-sw.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parses_all_three_articles_from_the_fixture() {
        List<NewsHeadline> news = MarketauxNewsProvider.parseNews(fixture(), 10);
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
        List<NewsHeadline> news = MarketauxNewsProvider.parseNews(body, 10);
        assertThat(news.get(0).summary()).isEqualTo("snippet text");
        assertThat(news.get(1).summary()).isEmpty();
    }

    @Test
    void max_caps_the_number_of_headlines_returned() {
        List<NewsHeadline> news = MarketauxNewsProvider.parseNews(fixture(), 2);
        assertThat(news).hasSize(2);
    }

    @Test
    void empty_data_array_yields_an_empty_list() {
        List<NewsHeadline> news = MarketauxNewsProvider.parseNews("{\"data\":[]}", 10);
        assertThat(news).isEmpty();
    }

    @Test
    void article_missing_required_fields_is_skipped_not_fatal() {
        String body = "{\"data\":[{\"title\":\"only a title\"},"
                + "{\"title\":\"no url\",\"published_at\":\"2026-05-14T06:30:00Z\",\"source\":\"x.com\"},"
                + "{\"title\":\"ok\",\"published_at\":\"2026-05-14T06:30:00Z\",\"source\":\"x.com\","
                + "\"url\":\"https://x.com/ok\"}]}";
        List<NewsHeadline> news = MarketauxNewsProvider.parseNews(body, 10);
        assertThat(news).hasSize(1);
        assertThat(news.get(0).title()).isEqualTo("ok");
    }

    @Test
    void article_with_unparsable_date_is_skipped() {
        String body = "{\"data\":[{\"title\":\"bad date\",\"published_at\":\"not-a-date\","
                + "\"source\":\"x.com\",\"url\":\"https://x.com/bad\"}]}";
        assertThat(MarketauxNewsProvider.parseNews(body, 10)).isEmpty();
    }

    @Test
    void non_json_body_raises_schema_drift() {
        assertThatThrownBy(() -> MarketauxNewsProvider.parseNews("<html>nope</html>", 10))
                .isInstanceOf(SchemaDriftException.class);
    }

    @Test
    void missing_data_field_raises_schema_drift() {
        assertThatThrownBy(() -> MarketauxNewsProvider.parseNews("{\"meta\":{}}", 10))
                .isInstanceOf(SchemaDriftException.class);
    }
}
