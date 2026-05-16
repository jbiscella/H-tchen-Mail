package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.news.NewsAggregator;
import com.heikinashi.monitoring.infrastructure.news.NewsConfig;
import com.heikinashi.monitoring.infrastructure.news.NewsProvider;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Drives {@link NewsAggregator} directly with stub providers — the aggregator
 * is a plain {@code @Singleton} over a list of {@link NewsProvider}, so no
 * Micronaut context or in-memory adapter is needed.
 */
public class NewsAggregationSteps {

    private final List<NewsProvider> providers = new ArrayList<>();
    private final NewsConfig config = new NewsConfig();
    private List<NewsHeadline> result;

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
