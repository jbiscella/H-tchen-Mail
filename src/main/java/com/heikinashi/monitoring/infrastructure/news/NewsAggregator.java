package com.heikinashi.monitoring.infrastructure.news;

import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
 *
 * <p><strong>Block 18 — order of operations.</strong> Providers are asked for
 * {@link NewsConfig#getCandidatePool()} items, not {@code max}: the cap is applied
 * <em>after</em> filtering, not before. Previously the merged list was sorted by recency
 * and truncated to {@code max} with nothing having assessed relevance, so a handful of
 * incidentally-tagged items could consume the entire budget and leave the AI analyst
 * with nothing usable. The two filters applied here need only {@link NewsHeadline}
 * fields, so one implementation covers every present and future provider:
 *
 * <ol>
 *   <li><b>Date window</b> — anything published before the timeframe's recency window
 *       is dropped. This is the only enforcement point that covers providers whose feed
 *       has no date filter of its own (Yahoo RSS), which is how items months older than
 *       the window used to reach the model.</li>
 *   <li><b>Promotional title shapes</b> — a global, instrument-independent pattern list
 *       (see {@link NewsConfig#getPromotionalTitlePatterns()}).</li>
 * </ol>
 *
 * <p>Entity-cardinality filtering is NOT here: the entity/symbol list lives in each
 * provider's raw payload and is deliberately not carried on {@link NewsHeadline}, so it
 * is applied inside the adapters.
 *
 * <p>Filtering everything out yields an empty list, which is a valid and more
 * informative answer than five irrelevant items — the AI prompt already instructs the
 * model to say so rather than pad the note.
 */
@Singleton
public class NewsAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(NewsAggregator.class);
    private static final Duration DEDUP_WINDOW = Duration.ofHours(1);

    private final List<NewsProvider> providers;
    private final NewsConfig config;
    private final RecencyWindowSource recency;
    private final Clock clock;

    public NewsAggregator(List<NewsProvider> providers, NewsConfig config, RecencyWindowSource recency, Clock clock) {
        this.providers = providers;
        this.config = config;
        this.recency = recency;
        this.clock = clock;
    }

    public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max, Timeframe tf) {
        List<NewsProvider> enabled = providers.stream()
                .filter(p -> config.getProviders().contains(p.name()))
                .toList();
        if (enabled.isEmpty()) {
            return List.of();
        }

        // Block 18: over-fetch. Each provider is asked for the candidate pool, not the
        // final cap — filtering happens below, so the pool has to be wide enough that
        // relevant items survive it.
        int poolPerProvider = Math.max(max, config.getCandidatePool());

        List<CompletableFuture<List<NewsHeadline>>> futures = new ArrayList<>(enabled.size());
        for (NewsProvider p : enabled) {
            futures.add(CompletableFuture.supplyAsync(() -> p.fetchNewsHeadlines(ticker, exchange, poolPerProvider, tf))
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
        int fetched = merged.size();

        Instant windowStart =
                recency.publishedAfter(clock, tf).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<NewsHeadline> inWindow = merged.stream()
                .filter(h -> !h.publishedAt().isBefore(windowStart))
                .toList();

        List<Pattern> promotional = promotionalPatterns();
        List<NewsHeadline> relevant = inWindow.stream()
                .filter(h -> !matchesAny(promotional, h.title()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        relevant.sort(Comparator.comparing(NewsHeadline::publishedAt).reversed());
        List<NewsHeadline> deduped = dedup(relevant);
        List<NewsHeadline> capped = deduped.size() > max ? new ArrayList<>(deduped.subList(0, max)) : deduped;

        LOG.info(
                "news_aggregate ticker={} tf={} fetched={} dropped_stale={} dropped_promotional={} deduped={} returned={} pool={} window_start={}",
                ticker,
                tf.wire(),
                fetched,
                fetched - inWindow.size(),
                inWindow.size() - relevant.size(),
                relevant.size() - deduped.size(),
                capped.size(),
                poolPerProvider,
                windowStart);
        return capped;
    }

    /**
     * The configured promotional-title patterns, compiled case-insensitively. An
     * unparsable pattern is logged and skipped rather than failing the alert: a typo in
     * an operator-tunable list must never cost the user their email.
     */
    private List<Pattern> promotionalPatterns() {
        List<Pattern> compiled = new ArrayList<>();
        for (String raw : config.getPromotionalTitlePatterns()) {
            try {
                compiled.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                LOG.warn("news_promotional_pattern_invalid pattern={} error={}", raw, e.getDescription());
            }
        }
        return compiled;
    }

    private static boolean matchesAny(List<Pattern> patterns, String title) {
        for (Pattern p : patterns) {
            if (p.matcher(title).matches()) {
                return true;
            }
        }
        return false;
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
