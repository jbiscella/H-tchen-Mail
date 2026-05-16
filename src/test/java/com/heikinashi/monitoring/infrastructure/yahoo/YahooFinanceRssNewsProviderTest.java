package com.heikinashi.monitoring.infrastructure.yahoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class YahooFinanceRssNewsProviderTest {

    private static String fixture() {
        try (var in = YahooFinanceRssNewsProviderTest.class.getResourceAsStream("/yahoo-rss-cfr-sw.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parses_all_three_items_from_the_fixture() {
        List<NewsHeadline> news = YahooFinanceRssNewsProvider.parseRss(fixture(), 10);
        assertThat(news).hasSize(3);
        NewsHeadline first = news.get(0);
        assertThat(first.title()).isEqualTo("Richemont shares climb after upbeat jewellery update");
        assertThat(first.url()).isEqualTo("https://finance.yahoo.com/news/richemont-shares-climb-0001.html");
        assertThat(first.source()).isEqualTo("Yahoo Finance");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-05-14T07:10:00Z"));
        assertThat(first.summary())
                .isEqualTo("The luxury group's jewellery maisons drove a stronger-than-expected quarter.");
        // Items 2 and 3 have no <description> in the feed — summary is empty, not null.
        assertThat(news.get(1).summary()).isEmpty();
    }

    @Test
    void max_caps_the_number_of_items_returned() {
        assertThat(YahooFinanceRssNewsProvider.parseRss(fixture(), 2)).hasSize(2);
    }

    @Test
    void item_missing_link_is_skipped() {
        String xml = "<rss><channel><item><title>no link</title>"
                + "<pubDate>Thu, 14 May 2026 07:10:00 +0000</pubDate></item></channel></rss>";
        assertThat(YahooFinanceRssNewsProvider.parseRss(xml, 10)).isEmpty();
    }

    @Test
    void item_with_unparsable_pubdate_is_skipped() {
        String xml = "<rss><channel><item><title>bad date</title>"
                + "<link>https://x.com/a</link><pubDate>not-a-date</pubDate></item></channel></rss>";
        assertThat(YahooFinanceRssNewsProvider.parseRss(xml, 10)).isEmpty();
    }

    @Test
    void feed_with_no_items_yields_an_empty_list() {
        String xml = "<rss><channel><title>empty</title></channel></rss>";
        assertThat(YahooFinanceRssNewsProvider.parseRss(xml, 10)).isEmpty();
    }

    @Test
    void non_xml_body_raises_schema_drift() {
        assertThatThrownBy(() -> YahooFinanceRssNewsProvider.parseRss("not xml at all", 10))
                .isInstanceOf(SchemaDriftException.class);
    }
}
