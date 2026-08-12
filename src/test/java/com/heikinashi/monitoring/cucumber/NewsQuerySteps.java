package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.infrastructure.news.NewsQueryResolver;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Optional;

/**
 * Steps for {@code features/news/tavily_news.feature} — Block 19 query resolution.
 *
 * <p>Only the query is exercised here. The HTTP-shaped behaviour is pinned by AAA tests at the
 * pure seams in {@code TavilyNewsProviderTest}; a Cucumber layer over a stubbed transport would
 * assert against the stub.
 */
public class NewsQuerySteps {

    private final World world;
    private String resolved;

    public NewsQuerySteps(World world) {
        this.world = world;
    }

    @Given("an instrument {string} on {string} named {string}")
    public void an_instrument_named(String ticker, String exchange, String name) {
        Instrument i = world.registry().register(ticker, exchange, Optional.of(name), Optional.empty());
        world.rememberInstrument(ticker, i);
    }

    @Given("an instrument {string} on {string} with no name")
    public void an_instrument_with_no_name(String ticker, String exchange) {
        Instrument i = world.registry().register(ticker, exchange, Optional.empty(), Optional.empty());
        world.rememberInstrument(ticker, i);
    }

    @Given("the news_query for {string} on {string} is {string}")
    public void the_news_query_is(String ticker, String exchange, String query) {
        setNewsQuery(ticker, exchange, Optional.of(query));
    }

    @Given("the news_query for {string} on {string} is cleared")
    public void the_news_query_is_cleared(String ticker, String exchange) {
        setNewsQuery(ticker, exchange, Optional.empty());
    }

    @When("the news query for {string} on {string} is resolved")
    public void the_news_query_is_resolved(String ticker, String exchange) {
        resolved = new NewsQueryResolver(world.repository()).queryFor(ticker, exchange);
    }

    @Then("the news query is {string}")
    public void the_news_query_should_be(String expected) {
        assertThat(resolved).isEqualTo(expected);
    }

    private void setNewsQuery(String ticker, String exchange, Optional<String> query) {
        String id = world.repository()
                .findByTickerAndExchange(ticker, exchange)
                .orElseThrow(() -> new IllegalStateException("no instrument " + ticker + " on " + exchange))
                .id();
        InstrumentConfig cfg =
                world.repository().findConfigById(id).orElseThrow(() -> new IllegalStateException("no config " + id));
        world.repository().updateConfig(id, cfg.withNewsQuery(query, world.now()));
    }
}
