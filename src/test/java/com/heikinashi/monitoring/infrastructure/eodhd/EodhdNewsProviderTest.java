package com.heikinashi.monitoring.infrastructure.eodhd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AAA tests for the EODHD news parsing/truncation helpers. The Gherkin
 * scenarios in news_aggregation.feature cover the end-to-end mapping; these
 * pin the off-by-one boundaries and skip/drift branches not worth enumerating
 * there (same split as MarketauxNewsProviderTest).
 */
class EodhdNewsProviderTest {

    /** Block 18 entity-cardinality ceiling; high enough that the pre-existing fixtures are unaffected. */
    private static final int MAX_ENTITIES = 6;

    // --- symbolCount / entity-cardinality filter (Block 18 step 3) ---

    @Test
    void an_item_with_more_symbols_than_the_ceiling_is_dropped() {
        String symbols = "[\"NVDA.US\",\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]"; // 7 > 6
        String body = "[{\"title\":\"Q2 2026 earnings call summaries\",\"date\":\"2026-06-12T10:00:00+00:00\","
                + "\"link\":\"https://a/x\",\"symbols\":" + symbols + "}]";

        EodhdNewsProvider.ParseResult out = EodhdNewsProvider.parseNews(body, 5, 600, MAX_ENTITIES);

        assertThat(out.headlines()).isEmpty();
        assertThat(out.droppedMultiEntity()).isEqualTo(1);
    }

    @Test
    void an_item_at_the_ceiling_is_kept() {
        String symbols = "[\"NVDA.US\",\"A\",\"B\",\"C\",\"D\",\"E\"]"; // exactly 6
        String body = "[{\"title\":\"kept\",\"date\":\"2026-06-12T10:00:00+00:00\","
                + "\"link\":\"https://a/x\",\"symbols\":" + symbols + "}]";

        EodhdNewsProvider.ParseResult out = EodhdNewsProvider.parseNews(body, 5, 600, MAX_ENTITIES);

        assertThat(out.headlines()).hasSize(1);
        assertThat(out.droppedMultiEntity()).isZero();
    }

    @Test
    void an_item_with_no_symbols_field_is_never_dropped_by_cardinality() {
        String body =
                "[{\"title\":\"no symbol list\",\"date\":\"2026-06-12T10:00:00+00:00\",\"link\":\"https://a/x\"}]";

        assertThat(EodhdNewsProvider.parseNews(body, 5, 600, MAX_ENTITIES).headlines())
                .hasSize(1);
    }

    @Test
    void a_dropped_digest_does_not_consume_a_slot_in_the_candidate_pool() {
        String digest = "{\"title\":\"digest\",\"date\":\"2026-06-12T11:00:00+00:00\",\"link\":\"https://a/d\","
                + "\"symbols\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\"]}";
        String single = "{\"title\":\"single\",\"date\":\"2026-06-12T10:00:00+00:00\",\"link\":\"https://a/s\","
                + "\"symbols\":[\"NVDA.US\"]}";
        // max=1: if the digest consumed the only slot, "single" would never be reached.
        String body = "[" + digest + "," + single + "]";

        assertThat(EodhdNewsProvider.parseNews(body, 1, 600, MAX_ENTITIES).headlines())
                .extracting(NewsHeadline::title)
                .containsExactly("single");
    }

    // --- summarize: word-boundary truncation boundaries ---

    @Test
    void content_exactly_at_the_limit_is_verbatim() {
        String content = "a".repeat(600);
        assertThat(EodhdNewsProvider.summarize(content, 600)).isEqualTo(content);
    }

    @Test
    void content_one_over_the_limit_is_truncated() {
        String content = "word ".repeat(120) + "x"; // 601 chars
        String summary = EodhdNewsProvider.summarize(content, 600);
        assertThat(summary).endsWith("…");
        assertThat(summary.length()).isLessThanOrEqualTo(600);
    }

    @Test
    void cut_lands_exactly_on_a_space() {
        // First 10 chars are "word word " — the space at index 9 is the cut point.
        String summary = EodhdNewsProvider.summarize("word word word", 10);
        assertThat(summary).isEqualTo("word word…");
    }

    @Test
    void no_space_within_the_limit_hard_cuts_at_the_limit() {
        String summary = EodhdNewsProvider.summarize("x".repeat(50), 10);
        assertThat(summary).isEqualTo("x".repeat(10) + "…");
    }

    @Test
    void leading_space_only_within_the_limit_hard_cuts() {
        // lastIndexOf(' ') == 0 would keep nothing; the hard cut applies instead.
        String summary = EodhdNewsProvider.summarize(" " + "x".repeat(50), 10);
        assertThat(summary).isEqualTo(" " + "x".repeat(9) + "…");
    }

    @Test
    void empty_content_stays_empty() {
        assertThat(EodhdNewsProvider.summarize("", 600)).isEmpty();
    }

    // --- hostOf: malformed links never fail ---

    @Test
    void host_is_extracted_from_a_well_formed_link() {
        assertThat(EodhdNewsProvider.hostOf("https://finance.yahoo.com/markets/a-b"))
                .isEqualTo("finance.yahoo.com");
    }

    @Test
    void link_without_a_host_yields_empty() {
        assertThat(EodhdNewsProvider.hostOf("not-a-url")).isEmpty();
    }

    @Test
    void unparsable_link_yields_empty() {
        assertThat(EodhdNewsProvider.hostOf("ht!tp://bro ken")).isEmpty();
    }

    // --- parseNews: skip and drift branches ---

    @Test
    void item_missing_required_fields_is_skipped_not_fatal() {
        String body = "[{\"title\":\"no date or link\"},"
                + "{\"title\":\"ok\",\"date\":\"2026-06-12T10:00:00+00:00\",\"link\":\"https://a/x\"}]";
        List<NewsHeadline> out =
                EodhdNewsProvider.parseNews(body, 5, 600, MAX_ENTITIES).headlines();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).title()).isEqualTo("ok");
        assertThat(out.get(0).publishedAt()).isEqualTo(Instant.parse("2026-06-12T10:00:00Z"));
        assertThat(out.get(0).summary()).isEmpty();
    }

    @Test
    void item_with_unparsable_date_is_skipped() {
        String body = "[{\"title\":\"bad date\",\"date\":\"yesterday\",\"link\":\"https://a/x\"}]";
        assertThat(EodhdNewsProvider.parseNews(body, 5, 600, MAX_ENTITIES).headlines())
                .isEmpty();
    }

    @Test
    void max_caps_the_number_of_headlines_returned() {
        String item = "{\"title\":\"t\",\"date\":\"2026-06-12T10:00:00+00:00\",\"link\":\"https://a/x\"}";
        String body = "[" + item + "," + item + "," + item + "]";
        assertThat(EodhdNewsProvider.parseNews(body, 2, 600, MAX_ENTITIES).headlines())
                .hasSize(2);
    }

    @Test
    void non_json_body_raises_schema_drift() {
        assertThatThrownBy(() -> EodhdNewsProvider.parseNews("<html>quota page</html>", 5, 600, MAX_ENTITIES))
                .isInstanceOf(SchemaDriftException.class);
    }

    @Test
    void non_array_body_raises_schema_drift() {
        assertThatThrownBy(() -> EodhdNewsProvider.parseNews("{\"data\":[]}", 5, 600, MAX_ENTITIES))
                .isInstanceOf(SchemaDriftException.class);
    }
}
