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
        Instant createdAt,
        Instant updatedAt) {

    public InstrumentConfig {
        Objects.requireNonNull(storagePolicy, "storagePolicy");
        Objects.requireNonNull(rollingWindowSize, "rollingWindowSize");
        Objects.requireNonNull(trackedTimeframes, "trackedTimeframes");
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(recipients, "recipients");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        trackedTimeframes = Set.copyOf(trackedTimeframes);
        recipients = Set.copyOf(recipients);
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
                createdAt,
                updatedAt);
    }
}
