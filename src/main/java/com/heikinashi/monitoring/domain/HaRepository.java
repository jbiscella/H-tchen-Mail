package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for Heikin-Ashi bars (CLAUDE.md §7). */
public interface HaRepository {

    /** The latest HA bar with {@code barTime < before}, or empty. */
    Optional<HABar> findLatestBefore(String instrumentId, Timeframe tf, Instant before);

    /**
     * Up to {@code n} most-recent HA bars with {@code barTime < before}, in
     * ascending bar_time order. Used by the pattern detector to pre-fetch the
     * streak-history window in a single Query (CLAUDE.md §8).
     */
    List<HABar> findLastNBefore(String instrumentId, Timeframe tf, Instant before, int n);

    /**
     * The most recent {@code n} HA bars with {@code barTime <= toInclusive}, in
     * ascending bar_time order. Inclusive twin of {@link #findLastNBefore}: used
     * by the strategy chart path (first attempt and retry re-render) to load a
     * bar-counted window ending at the alert bar, so a referenced indicator has
     * enough bars to render regardless of market-closure gaps. Empty when
     * {@code n <= 0}.
     */
    List<HABar> findLastN(String instrumentId, Timeframe tf, Instant toInclusive, int n);

    /**
     * Put (overwrite). Same OHLC → same HA, so writing again is a no-op
     * functionally. Optional epoch-seconds TTL.
     */
    void putBar(HABar bar, Optional<Long> ttl);

    /** SNAPSHOT_ONLY truncate-and-put (TransactWrite when ≤24, batched otherwise). */
    void snapshotReplace(String instrumentId, Timeframe tf, HABar newBar, Optional<Long> ttl);

    /** All HA bars for a (instrument, tf), ascending by bar_time. */
    List<HABar> listAll(String instrumentId, Timeframe tf);

    /** Delete every HA bar for (instrument, tf). Used by bulk recompute. */
    void deleteAll(String instrumentId, Timeframe tf);
}
