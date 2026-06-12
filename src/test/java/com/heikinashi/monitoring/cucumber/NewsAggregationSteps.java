package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.eodhd.EodhdConfig;
import com.heikinashi.monitoring.infrastructure.eodhd.EodhdNewsConfig;
import com.heikinashi.monitoring.infrastructure.eodhd.EodhdNewsProvider;
import com.heikinashi.monitoring.infrastructure.marketaux.MarketauxConfig;
import com.heikinashi.monitoring.infrastructure.news.NewsAggregator;
import com.heikinashi.monitoring.infrastructure.news.NewsConfig;
import com.heikinashi.monitoring.infrastructure.news.NewsProvider;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives {@link NewsAggregator} directly with stub providers — the aggregator
 * is a plain {@code @Singleton} over a list of {@link NewsProvider}, so no
 * Micronaut context or in-memory adapter is needed.
 *
 * <p>The Block 17 scenarios additionally compose the <em>real</em>
 * {@link EodhdNewsProvider} into the aggregator, scripted through its
 * {@link EodhdNewsProvider.HttpExchange} seam: the steps record every queried
 * URI (symbol / recency-window assertions) and answer with canned EODHD News
 * API JSON, so the provider's mapping runs end-to-end without a network.
 */
public class NewsAggregationSteps {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-12T15:00:00Z"), ZoneOffset.UTC);

    private final List<NewsProvider> providers = new ArrayList<>();
    private final NewsConfig config = new NewsConfig();
    private List<NewsHeadline> result;

    // Block 17 — EODHD provider state.
    private final EodhdNewsConfig eodhdNewsConfig = new EodhdNewsConfig();
    private final MarketauxConfig recencySource = new MarketauxConfig();
    private final List<ObjectNode> eodhdItems = new ArrayList<>();
    private final List<URI> eodhdQueries = new ArrayList<>();
    private boolean eodhdFails;
    private Timeframe timeframe = Timeframe.D1;
    private String lastEodhdContent;

    @Given("a news provider {string} returning:")
    public void a_news_provider_returning(String name, DataTable table) {
        List<NewsHeadline> headlines = new ArrayList<>();
        for (Map<String, String> row : table.asMaps()) {
            headlines.add(new NewsHeadline(
                    row.get("title"), Instant.parse(row.get("published_at")), "src", orEmpty(row.get("url")), ""));
        }
        providers.add(stub(name, headlines, false));
    }

    @Given("a news provider {string} that fails")
    public void a_news_provider_that_fails(String name) {
        providers.add(stub(name, List.of(), true));
    }

    @Given("the enabled news providers are {string}")
    public void the_enabled_news_providers_are(String csv) {
        List<String> names = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setProviders(names);
    }

    @When("I aggregate news with max {int}")
    public void i_aggregate_news_with_max(int max) {
        result = new NewsAggregator(providers, config).fetchNewsHeadlines("CFR", "SWX", max, Timeframe.D1);
    }

    @Then("the aggregated headlines are {string}")
    public void the_aggregated_headlines_are(String csv) {
        List<String> expected = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        assertThat(result).extracting(NewsHeadline::title).containsExactlyElementsOf(expected);
    }

    @Then("the aggregated result has {int} headlines")
    public void the_aggregated_result_has_headlines(int n) {
        assertThat(result).hasSize(n);
    }

    @Then("the aggregated result is empty")
    public void the_aggregated_result_is_empty() {
        assertThat(result).isEmpty();
    }

    // ------------------------------------------------------------------
    // Block 17 — EODHD provider steps
    // ------------------------------------------------------------------

    @Given("the EODHD provider returns a headline published at {string} for {string} on {string}")
    public void eodhd_returns_a_headline_published_at(String publishedAt, String ticker, String exchange) {
        eodhdItems.add(item(publishedAt, "eodhd story " + (eodhdItems.size() + 1), linkFor(), "short content"));
    }

    @Given("the Marketaux provider returns a headline published at {string} for {string} on {string}")
    public void marketaux_returns_a_headline_published_at(String publishedAt, String ticker, String exchange) {
        providers.add(stub(
                "marketaux",
                List.of(new NewsHeadline(
                        "marketaux story", Instant.parse(publishedAt), "src", "https://news.example.com/mx-1", "")),
                false));
    }

    @Given("the Marketaux provider returns {int} headline for {string} on {string}")
    public void marketaux_returns_n_headlines(int n, String ticker, String exchange) {
        List<NewsHeadline> headlines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            headlines.add(new NewsHeadline(
                    "marketaux story " + i,
                    Instant.parse("2026-06-12T08:00:00Z").minusSeconds(i * 7200L),
                    "src",
                    "https://news.example.com/mx-" + i,
                    ""));
        }
        providers.add(stub("marketaux", headlines, false));
    }

    @Given("the summary limit is {int} characters")
    public void the_summary_limit_is(int chars) {
        eodhdNewsConfig.setSummaryMaxChars(chars);
    }

    @Given("the EODHD provider returns a headline whose content is {int} characters long")
    public void eodhd_returns_content_of_length(int chars) {
        String content = "lorem ipsum dolor sit amet ".repeat(chars / 10 + 1).substring(0, chars);
        lastEodhdContent = content;
        eodhdItems.add(item("2026-06-12T10:00:00Z", "long body", linkFor(), content));
    }

    @Given("the EODHD provider returns a headline whose content is a single 4000-character word")
    public void eodhd_returns_a_single_long_word() {
        String content = "x".repeat(4000);
        lastEodhdContent = content;
        eodhdItems.add(item("2026-06-12T10:00:00Z", "one giant word", linkFor(), content));
    }

    @Given("the EODHD provider returns a headline with no content")
    public void eodhd_returns_a_headline_without_content() {
        eodhdItems.add(item("2026-06-12T10:00:00Z", "no body", linkFor(), null));
    }

    @Given("the EODHD provider returns a headline linking to {string}")
    public void eodhd_returns_a_headline_linking_to(String link) {
        eodhdItems.add(item("2026-06-12T10:00:00Z", "linked story", link, "short content"));
    }

    @Given("the EODHD provider returns a headline whose link is not a valid URL")
    public void eodhd_returns_a_headline_with_malformed_link() {
        eodhdItems.add(item("2026-06-12T10:00:00Z", "broken link story", "ht!tp://bro ken", "short content"));
    }

    @Given("the EODHD provider returns a headline dated {string}")
    public void eodhd_returns_a_headline_dated(String date) {
        eodhdItems.add(item(date, "offset story", linkFor(), "short content"));
    }

    @Given("the EODHD provider fails")
    public void the_eodhd_provider_fails() {
        eodhdFails = true;
    }

    @Given("the pattern timeframe is {string}")
    public void the_pattern_timeframe_is(String wire) {
        timeframe = Timeframe.fromWire(wire);
    }

    @Given("an instrument {string} on {string}")
    public void an_instrument_on(String ticker, String exchange) {
        // Context only — the When step names the instrument it fetches for.
    }

    @When("I fetch news headlines for {string} on {string} with max {int}")
    public void i_fetch_news_headlines(String ticker, String exchange, int max) {
        providers.add(eodhdProvider());
        result = new NewsAggregator(providers, config).fetchNewsHeadlines(ticker, exchange, max, timeframe);
    }

    @Then("{int} headlines are returned")
    public void n_headlines_are_returned(int n) {
        assertThat(result).hasSize(n);
    }

    @Then("1 headline is returned")
    public void one_headline_is_returned() {
        assertThat(result).hasSize(1);
    }

    @Then("the first headline is the one published at {string}")
    public void the_first_headline_is_published_at(String publishedAt) {
        assertThat(result.get(0).publishedAt()).isEqualTo(Instant.parse(publishedAt));
    }

    @Then("the returned summary is at most {int} characters")
    public void the_returned_summary_is_at_most(int chars) {
        assertThat(result).hasSize(1);
        assertThat(result.get(0).summary().length()).isLessThanOrEqualTo(chars);
    }

    @Then("the summary ends at a word boundary with an ellipsis")
    public void the_summary_ends_at_a_word_boundary_with_ellipsis() {
        String summary = result.get(0).summary();
        assertThat(summary).endsWith("…");
        String body = summary.substring(0, summary.length() - 1);
        // The cut dropped the word-boundary space: the original continues with one.
        assertThat(lastEodhdContent).startsWith(body);
        assertThat(lastEodhdContent.charAt(body.length())).isEqualTo(' ');
    }

    @Then("the returned summary is the content verbatim, without ellipsis")
    public void the_returned_summary_is_verbatim() {
        assertThat(result).hasSize(1);
        assertThat(result.get(0).summary()).isEqualTo(lastEodhdContent).doesNotEndWith("…");
    }

    @Then("the returned summary is exactly {int} characters plus an ellipsis")
    public void the_returned_summary_is_exactly_n_plus_ellipsis(int chars) {
        String summary = result.get(0).summary();
        assertThat(summary).endsWith("…").hasSize(chars + 1);
        assertThat(summary.substring(0, chars)).isEqualTo(lastEodhdContent.substring(0, chars));
    }

    @Then("1 headline is returned with an empty summary")
    public void one_headline_with_empty_summary() {
        assertThat(result).hasSize(1);
        assertThat(result.get(0).summary()).isEmpty();
    }

    @Then("the headline source is {string}")
    public void the_headline_source_is(String source) {
        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEqualTo(source);
    }

    @Then("1 headline is returned with an empty source")
    public void one_headline_with_empty_source() {
        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEmpty();
    }

    @Then("the headline publishedAt is {string}")
    public void the_headline_published_at_is(String instant) {
        assertThat(result).hasSize(1);
        assertThat(result.get(0).publishedAt()).isEqualTo(Instant.parse(instant));
    }

    @Then("the EODHD provider is never queried")
    public void the_eodhd_provider_is_never_queried() {
        assertThat(eodhdQueries).isEmpty();
    }

    @Then("the EODHD provider was queried with symbol {string}")
    public void the_eodhd_provider_was_queried_with_symbol(String symbol) {
        assertThat(eodhdQueries).hasSize(1);
        assertThat(queryParams(eodhdQueries.get(0))).containsEntry("s", symbol);
    }

    @Then("the EODHD provider was queried with the same recency window as the Marketaux provider")
    public void the_eodhd_recency_window_matches_marketaux() {
        assertThat(eodhdQueries).hasSize(1);
        Map<String, String> params = queryParams(eodhdQueries.get(0));
        LocalDate marketauxWindowStart = recencySource.publishedAfter(FIXED_CLOCK, timeframe);
        assertThat(params).containsEntry("from", marketauxWindowStart.toString());
        assertThat(params)
                .containsEntry(
                        "to",
                        LocalDate.ofInstant(FIXED_CLOCK.instant(), ZoneOffset.UTC)
                                .toString());
    }

    // ------------------------------------------------------------------
    // Block 17 helpers
    // ------------------------------------------------------------------

    private EodhdNewsProvider eodhdProvider() {
        EodhdConfig eodhdConfig = new EodhdConfig();
        eodhdConfig.setApiKey("test-token");
        EodhdNewsProvider.HttpExchange http = uri -> {
            eodhdQueries.add(uri);
            if (eodhdFails) {
                throw new IOException("scripted EODHD failure");
            }
            return new EodhdNewsProvider.HttpExchange.Result(
                    200, JSON.createArrayNode().addAll(eodhdItems).toString());
        };
        return new EodhdNewsProvider(
                eodhdConfig,
                eodhdNewsConfig,
                Map.of("NASDAQ", ".US", "MIL", ".MI"),
                recencySource,
                FIXED_CLOCK,
                http);
    }

    private static ObjectNode item(String date, String title, String link, String content) {
        ObjectNode node = JSON.createObjectNode();
        node.put("date", date);
        node.put("title", title);
        node.put("link", link);
        if (content != null) {
            node.put("content", content);
        }
        return node;
    }

    /** A unique, well-formed link per scripted item so the aggregator's URL dedup never collapses them. */
    private String linkFor() {
        return "https://news.example.com/eodhd-" + (eodhdItems.size() + 1);
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static NewsProvider stub(String name, List<NewsHeadline> headlines, boolean fails) {
        return new NewsProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max, Timeframe tf) {
                if (fails) {
                    throw new RuntimeException("provider " + name + " is down");
                }
                return headlines;
            }
        };
    }
}
