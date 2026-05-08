package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage-policy → TTL math (CLAUDE.md §2 / §6).
 *
 * <p>{@link Optional#empty()} means no TTL attribute should be written.
 */
public final class StoragePolicies {

    private StoragePolicies() {}

    public static Optional<Long> computeTtl(InstrumentConfig cfg, Instant barTime, Timeframe tf) {
        return switch (cfg.storagePolicy()) {
            case FULL_HISTORY -> Optional.empty();
            case ROLLING_WINDOW -> {
                int n = cfg.rollingWindowSize()
                        .orElseThrow(
                                () -> new IllegalStateException("ROLLING_WINDOW config without rolling_window_size"));
                long period = Timeframes.periodSeconds(tf);
                long grace = period; // 1-period grace
                yield Optional.of(barTime.getEpochSecond() + n * period + grace);
            }
            case SNAPSHOT_ONLY -> Optional.empty();
        };
    }
}
