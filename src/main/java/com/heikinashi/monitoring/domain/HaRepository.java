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
