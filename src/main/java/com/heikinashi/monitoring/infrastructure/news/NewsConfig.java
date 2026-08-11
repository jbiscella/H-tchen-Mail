package com.heikinashi.monitoring.infrastructure.news;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * Which news adapters {@link NewsAggregator} queries, and the Block 18 candidate-pool
 * knobs it filters with. A provider whose {@link NewsProvider#name()} is not in
 * {@link #getProviders()} is skipped — letting an operator disable a source without
 * removing its code.
 *
 * <p>Block 18 changed the order of operations from fetch-{@code max} → sort → cap to
 * scope → fetch {@link #getCandidatePool()} → filter → rank → cap to {@code max}. The
 * old order applied the cap before anything assessed relevance, so a handful of
 * incidentally-tagged items could consume the whole budget and leave the AI analyst
 * with nothing usable.
 *
 * <p>Every knob here is <strong>instrument-generic</strong>: none of them is keyed by
 * ticker, company name or exchange. H-tchen supports any stock on any supported
 * exchange, so per-instrument curation is not an option — the filters work off
 * provider-resolved entities and structural properties of the item itself.
 */
@ConfigurationProperties("monitoring.news")
public class NewsConfig {

    private List<String> providers = List.of("marketaux", "yahoo-rss", "eodhd");

    /**
     * How many items each provider is asked for before filtering. Deliberately far
     * larger than the {@code max} eventually handed to the AI analyst: filtering
     * happens after the fetch, so the pool has to be wide enough that relevant items
     * survive it.
     */
    @Min(1)
    private int candidatePool = 30;

    /**
     * Ceiling on how many entities/symbols a single item may carry before it is read
     * as a multi-company digest and dropped. An article about one company carries one
     * or a few tickers; a "Q2 earnings call summaries" round-up carries a dozen. This
     * is the generic digest filter — it never needs to know <em>which</em> company the
     * alert is about.
     *
     * <p>6 is a starting estimate, not a measured threshold. Providers log the number
     * of items they drop on this rule so it can be tuned on evidence.
     */
    @Min(1)
    private int maxEntitiesPerItem = 6;

    /**
     * Case-insensitive regular expressions matched against an item's title; a match
     * drops the item. The list is <strong>global</strong> — one set of shapes for every
     * instrument, never per-instrument patterns.
     *
     * <p>The defaults encode the two advertorial shapes that the 15-email study found
     * the AI analyst discarding by hand: the stock-screener "X vs Y: Which Is the
     * Better ... ?" comparison, and the numeric listicle ("3 Stocks To ...").
     */
    private List<String> promotionalTitlePatterns =
            List.of(".*\\bvs\\.?\\b.*\\?\\s*$", "^\\s*\\d+\\s+(stocks?|shares?|reasons?|things)\\b.*");

    public List<String> getProviders() {
        return providers;
    }

    public void setProviders(List<String> providers) {
        this.providers = providers;
    }

    public int getCandidatePool() {
        return candidatePool;
    }

    public void setCandidatePool(int candidatePool) {
        this.candidatePool = candidatePool;
    }

    public int getMaxEntitiesPerItem() {
        return maxEntitiesPerItem;
    }

    public void setMaxEntitiesPerItem(int maxEntitiesPerItem) {
        this.maxEntitiesPerItem = maxEntitiesPerItem;
    }

    public List<String> getPromotionalTitlePatterns() {
        return promotionalTitlePatterns;
    }

    public void setPromotionalTitlePatterns(List<String> promotionalTitlePatterns) {
        this.promotionalTitlePatterns = promotionalTitlePatterns;
    }
}
