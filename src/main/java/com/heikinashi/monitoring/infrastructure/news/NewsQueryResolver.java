package com.heikinashi.monitoring.infrastructure.news;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The query a web-search news provider should send for an instrument (Block 19).
 *
 * <p>A search engine cannot be asked nothing, and the bare ticker is actively harmful: measured
 * against Tavily, {@code AMS.MC} returned AWS single-account landing-zone documentation,
 * Amsterdam flight listings and Perplexity. So the query is the instrument's <em>name</em> plus
 * its ticker, with an operator override for the awkward cases.
 *
 * <p><strong>This is a query, not a filter.</strong> Block 18 Part B rejected per-instrument
 * text configuration, and that rule stands for <em>filters</em> — client-side guessing about
 * whether an item concerns the company. Every relevance decision remains provider-side
 * ({@code topic=news}, {@code include_domains}) or structural (the recency window, the
 * promotional title shapes, the entity-cardinality ceiling). Block 19 records the reversal and
 * its reasoning.
 */
@Singleton
public class NewsQueryResolver {

    private static final Logger LOG = LoggerFactory.getLogger(NewsQueryResolver.class);

    private final InstrumentRepository instruments;

    public NewsQueryResolver(InstrumentRepository instruments) {
        this.instruments = instruments;
    }

    /**
     * The query for {@code ticker} on {@code exchange}:
     *
     * <ol>
     *   <li>the {@code news_query} override from the instrument's config, verbatim, if set;
     *   <li>otherwise {@code <name> <ticker> shares} — the shape measured to return the
     *       Amadeus guidance downgrade that the ticker-scoped APIs missed;
     *   <li>otherwise, when the instrument or its name is unknown, the ticker alone. That is
     *       the known-poor query, so it is logged at WARN: it is the signal that an override is
     *       needed, which is the whole point of the field being operator-settable.
     * </ol>
     */
    public String queryFor(String ticker, String exchange) {
        Optional<Instrument> instrument = instruments.findByTickerAndExchange(ticker, exchange);
        Optional<String> override = instrument
                .map(Instrument::id)
                .flatMap(instruments::findConfigById)
                .flatMap(InstrumentConfig::newsQuery);
        if (override.isPresent()) {
            return override.get();
        }
        Optional<String> name =
                instrument.flatMap(Instrument::name).map(String::trim).filter(n -> !n.isEmpty());
        if (name.isEmpty()) {
            LOG.warn(
                    "news_query_degraded ticker={} exchange={} reason={} advice=set_news_query_override",
                    ticker,
                    exchange,
                    instrument.isEmpty() ? "instrument_not_found" : "name_absent");
            return ticker;
        }
        return name.get() + " " + ticker + " shares";
    }
}
