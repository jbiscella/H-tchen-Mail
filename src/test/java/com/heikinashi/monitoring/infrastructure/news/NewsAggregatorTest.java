package com.heikinashi.monitoring.infrastructure.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsAggregatorTest {

    private static NewsHeadline headline(String title, String isoTime, String url) {
        return new NewsHeadline(title, Instant.parse(isoTime), "src", url, "");
    }

    /** A NewsProvider stub that returns a fixed list, or throws if {@code boom} is set. */
    private static NewsProvider provider(String name, List<NewsHeadline> result, RuntimeException boom) {
        return new NewsProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max, Timeframe tf) {
                if (boom != null) {
                    throw boom;
                }
                return result;
            }
        };
    }

    private static NewsConfig config(String... providers) {
        NewsConfig c = new NewsConfig();
        c.setProviders(List.of(providers));
        return c;
    }

    @Test
    void merges_both_providers_sorted_newest_first() {
        NewsProvider a = provider("a", List.of(headline("older", "2026-05-10T00:00:00Z", "u1")), null);
        NewsProvider b = provider("b", List.of(headline("newer", "2026-05-12T00:00:00Z", "u2")), null);
        NewsAggregator agg = new NewsAggregator(List.of(a, b), config("a", "b"));

        List<NewsHeadline> out = agg.fetchNewsHeadlines("CFR", "SWX", 10, Timeframe.D1);

        assertThat(out).extracting(NewsHeadline::title).containsExactly("newer", "older");
    }

    @Test
    void a_failing_provider_is_dropped_not_fatal() {
        NewsProvider ok = provider("ok", List.of(headline("survives", "2026-05-12T00:00:00Z", "u1")), null);
        NewsProvider bad = provider("bad", List.of(), new RuntimeException("boom"));
        NewsAggregator agg = new NewsAggregator(List.of(ok, bad), config("ok", "bad"));

        List<NewsHeadline> out = agg.fetchNewsHeadlines("CFR", "SWX", 10, Timeframe.D1);

        assertThat(out).extracting(NewsHeadline::title).containsExactly("survives");
    }

    @Test
    void disabled_provider_is_not_queried() {
        NewsProvider a = provider("a", List.of(headline("kept", "2026-05-12T00:00:00Z", "u1")), null);
        NewsProvider b = provider("b", List.of(headline("dropped", "2026-05-13T00:00:00Z", "u2")), null);
        NewsAggregator agg = new NewsAggregator(List.of(a, b), config("a"));

        List<NewsHeadline> out = agg.fetchNewsHeadlines("CFR", "SWX", 10, Timeframe.D1);

        assertThat(out).extracting(NewsHeadline::title).containsExactly("kept");
    }

    @Test
    void limit_is_applied_after_merge() {
        NewsProvider a = provider(
                "a",
                List.of(headline("h1", "2026-05-12T00:00:00Z", "u1"), headline("h2", "2026-05-11T00:00:00Z", "u2")),
                null);
        NewsProvider b = provider("b", List.of(headline("h3", "2026-05-10T00:00:00Z", "u3")), null);
        NewsAggregator agg = new NewsAggregator(List.of(a, b), config("a", "b"));

        assertThat(agg.fetchNewsHeadlines("CFR", "SWX", 2, Timeframe.D1)).hasSize(2);
    }

    @Test
    void dedup_drops_exact_url_match() {
        List<NewsHeadline> in = List.of(
                headline("Title one", "2026-05-12T10:00:00Z", "https://x.com/a"),
                headline("Different headline text", "2026-05-12T09:00:00Z", "https://x.com/a"));
        assertThat(NewsAggregator.dedup(in)).hasSize(1);
    }

    @Test
    void dedup_drops_same_title_within_one_hour() {
        List<NewsHeadline> in = List.of(
                headline("Richemont posts record sales", "2026-05-12T10:00:00Z", "https://a.com/x"),
                headline("RICHEMONT  posts   record sales", "2026-05-12T10:45:00Z", "https://b.com/y"));
        assertThat(NewsAggregator.dedup(in)).hasSize(1);
    }

    @Test
    void dedup_keeps_same_title_more_than_one_hour_apart() {
        List<NewsHeadline> in = List.of(
                headline("Richemont posts record sales", "2026-05-12T12:00:00Z", "https://a.com/x"),
                headline("Richemont posts record sales", "2026-05-12T10:00:00Z", "https://b.com/y"));
        assertThat(NewsAggregator.dedup(in)).hasSize(2);
    }

    @Test
    void empty_url_does_not_collapse_unrelated_headlines() {
        List<NewsHeadline> in = List.of(
                headline("First story", "2026-05-12T12:00:00Z", ""),
                headline("Second unrelated story", "2026-05-12T11:00:00Z", ""));
        assertThat(NewsAggregator.dedup(in)).hasSize(2);
    }

    @Test
    void normalize_title_lowercases_and_collapses_whitespace() {
        assertThat(NewsAggregator.normalizeTitle("  Big   NEWS\tHere ")).isEqualTo("big news here");
    }
}
