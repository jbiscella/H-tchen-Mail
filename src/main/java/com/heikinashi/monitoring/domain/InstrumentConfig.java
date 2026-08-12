package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Per-instrument configuration. Immutable record; mutators return new instances.
 *
 * <p>Defaults per CLAUDE.md §5 background:
 * <ul>
 *   <li>storage_policy = ROLLING_WINDOW, rolling_window_size = 200</li>
 *   <li>tracked_timeframes = ["1d"]</li>
 *   <li>patterns: all disabled (with default thresholds)</li>
 *   <li>recipients: empty</li>
 *   <li>enable_chart = true, enable_ai_analysis = true</li>
 *   <li>news_query = absent (the web-search query is derived from the instrument name)</li>
 * </ul>
 */
public record InstrumentConfig(
        StoragePolicy storagePolicy,
        Optional<Integer> rollingWindowSize,
        Set<Timeframe> trackedTimeframes,
        PatternsConfig patterns,
        Set<String> recipients,
        boolean enableChart,
        boolean enableAiAnalysis,
        Optional<String> newsQuery,
        Instant createdAt,
        Instant updatedAt) {

    /** Longest accepted override — one query string, not a rule language (Block 19). */
    public static final int MAX_NEWS_QUERY_LENGTH = 200;

    public InstrumentConfig {
        Objects.requireNonNull(storagePolicy, "storagePolicy");
        Objects.requireNonNull(rollingWindowSize, "rollingWindowSize");
        Objects.requireNonNull(trackedTimeframes, "trackedTimeframes");
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(recipients, "recipients");
        Objects.requireNonNull(newsQuery, "newsQuery");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        trackedTimeframes = Set.copyOf(trackedTimeframes);
        recipients = Set.copyOf(recipients);
        // A blank override would send an empty query and quietly return junk; treat it as
        // absent so the derived query takes over. Length-capped because this is a query, not
        // a place to grow a filter language (Block 19's narrow reversal).
        newsQuery = newsQuery.map(String::trim).filter(q -> !q.isEmpty());
        if (newsQuery.isPresent() && newsQuery.get().length() > MAX_NEWS_QUERY_LENGTH) {
            throw new IllegalArgumentException("newsQuery exceeds " + MAX_NEWS_QUERY_LENGTH + " characters: "
                    + newsQuery.get().length());
        }
    }

    /**
     * Pre-Block-19 shape, defaulting {@code newsQuery} to absent. Kept so the existing
     * construction sites and any stored config written before this field read back unchanged.
     */
    public InstrumentConfig(
            StoragePolicy storagePolicy,
            Optional<Integer> rollingWindowSize,
            Set<Timeframe> trackedTimeframes,
            PatternsConfig patterns,
            Set<String> recipients,
            boolean enableChart,
            boolean enableAiAnalysis,
            Instant createdAt,
            Instant updatedAt) {
        this(
                storagePolicy,
                rollingWindowSize,
                trackedTimeframes,
                patterns,
                recipients,
                enableChart,
                enableAiAnalysis,
                Optional.empty(),
                createdAt,
                updatedAt);
    }

    public static InstrumentConfig defaults(Instant now) {
        return new InstrumentConfig(
                StoragePolicy.ROLLING_WINDOW,
                Optional.of(200),
                Set.of(Timeframe.D1),
                PatternsConfig.defaults(),
                Set.of(),
                true,
                true,
                now,
                now);
    }

    public InstrumentConfig withStoragePolicy(StoragePolicy newPolicy, Optional<Integer> newWindow, Instant updatedAt) {
        return new InstrumentConfig(
                newPolicy,
                newWindow,
                trackedTimeframes,
                patterns,
                recipients,
                enableChart,
                enableAiAnalysis,
                newsQuery,
                createdAt,
                updatedAt);
    }

    public InstrumentConfig withTrackedTimeframes(Set<Timeframe> newTimeframes, Instant updatedAt) {
        return new InstrumentConfig(
                storagePolicy,
                rollingWindowSize,
                newTimeframes,
                patterns,
                recipients,
                enableChart,
                enableAiAnalysis,
                newsQuery,
                createdAt,
                updatedAt);
    }

    public InstrumentConfig withPatterns(PatternsConfig newPatterns, Instant updatedAt) {
        return new InstrumentConfig(
                storagePolicy,
                rollingWindowSize,
                trackedTimeframes,
                newPatterns,
                recipients,
                enableChart,
                enableAiAnalysis,
                newsQuery,
                createdAt,
                updatedAt);
    }

    public InstrumentConfig withRecipients(Set<String> newRecipients, Instant updatedAt) {
        return new InstrumentConfig(
                storagePolicy,
                rollingWindowSize,
                trackedTimeframes,
                patterns,
                newRecipients,
                enableChart,
                enableAiAnalysis,
                newsQuery,
                createdAt,
                updatedAt);
    }

    /**
     * Set or clear the web-search query override (Block 19). Every other wither threads
     * {@code newsQuery} through unchanged — the pre-Block-19 convenience constructor defaults
     * it to absent, so a wither that forgot it would silently erase an operator's override.
     */
    public InstrumentConfig withNewsQuery(Optional<String> newQuery, Instant updatedAt) {
        return new InstrumentConfig(
                storagePolicy,
                rollingWindowSize,
                trackedTimeframes,
                patterns,
                recipients,
                enableChart,
                enableAiAnalysis,
                newQuery,
                createdAt,
                updatedAt);
    }
}
