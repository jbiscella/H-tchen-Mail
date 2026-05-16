package com.heikinashi.monitoring.infrastructure.news;

import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fans {@code fetchNewsHeadlines} out across every enabled {@link NewsProvider},
 * in parallel, then merges the results: a single provider failing is logged and
 * dropped (it does not fail the whole call), the merged list is de-duplicated,
 * sorted newest-first, and capped at {@code max}.
 *
 * <p>Two headlines are duplicates when they share an exact URL, or when their
 * titles normalise equal and their publish times are within one hour — the same
 * story carried by two sources rarely lands more than an hour apart.
 */
@Singleton
public class NewsAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(NewsAggregator.class);
    private static final Duration DEDUP_WINDOW = Duration.ofHours(1);

    private final List<NewsProvider> providers;
    private final NewsConfig config;

    public NewsAggregator(List<NewsProvider> providers, NewsConfig config) {
        this.providers = providers;
        this.config = config;
    }

    public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max, Timeframe tf) {
        List<NewsProvider> enabled = providers.stream()
                .filter(p -> config.getProviders().contains(p.name()))
                .toList();
        if (enabled.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<List<NewsHeadline>>> futures = new ArrayList<>(enabled.size());
        for (NewsProvider p : enabled) {
            futures.add(CompletableFuture.supplyAsync(() -> p.fetchNewsHeadlines(ticker, exchange, max, tf))
                    .exceptionally(t -> {
                        LOG.warn(
                                "news_provider_failed provider={} ticker={} exchange={} error={}",
                                p.name(),
                                ticker,
                                exchange,
                                t.toString());
                        return List.of();
                    }));
        }

        List<NewsHeadline> merged = new ArrayList<>();
        for (CompletableFuture<List<NewsHeadline>> f : futures) {
            merged.addAll(f.join());
        }
        merged.sort(Comparator.comparing(NewsHeadline::publishedAt).reversed());

        List<NewsHeadline> deduped = dedup(merged);
        return deduped.size() > max ? new ArrayList<>(deduped.subList(0, max)) : deduped;
    }

    /** De-duplicate a list already sorted newest-first; keeps the first (newest) of each duplicate set. */
    static List<NewsHeadline> dedup(List<NewsHeadline> sortedNewestFirst) {
        List<NewsHeadline> kept = new ArrayList<>();
        for (NewsHeadline h : sortedNewestFirst) {
            boolean duplicate = false;
            for (NewsHeadline k : kept) {
                if (sameUrl(h, k) || sameStoryWithinWindow(h, k)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                kept.add(h);
            }
        }
        return kept;
    }

    private static boolean sameUrl(NewsHeadline a, NewsHeadline b) {
        return !a.url().isEmpty() && a.url().equals(b.url());
    }

    private static boolean sameStoryWithinWindow(NewsHeadline a, NewsHeadline b) {
        return normalizeTitle(a.title()).equals(normalizeTitle(b.title()))
                && Duration.between(a.publishedAt(), b.publishedAt()).abs().compareTo(DEDUP_WINDOW) <= 0;
    }

    static String normalizeTitle(String title) {
        return title.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
