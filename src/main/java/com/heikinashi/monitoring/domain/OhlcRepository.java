package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for OHLC bars (CLAUDE.md §6 / §7). Implementations live in
 * {@code infrastructure}.
 */
public interface OhlcRepository {

    /** Most recent bar for {@code (instrumentId, tf)} by {@code bar_time}, or empty. */
    Optional<OHLCBar> findLatest(String instrumentId, Timeframe tf);

    /** All OHLC bars at-or-after {@code from}, sorted ascending by bar_time. Used by HA recompute. */
    List<OHLCBar> findRange(String instrumentId, Timeframe tf, Instant from);

    /**
     * Idempotent put: writes the bar with a conditional non-existence check on
     * the primary key. Returns {@code true} if the bar was inserted, {@code false}
     * if a bar with the same {@code (instrument_id, tf, bar_time)} already
     * existed (treated as a no-op).
     *
     * @param ttl optional epoch-seconds TTL; {@link Optional#empty()} means no TTL.
     */
    boolean putBar(OHLCBar bar, Optional<Long> ttl);

    /**
     * SNAPSHOT_ONLY truncate-and-put. Atomically deletes existing bars for
     * {@code (instrumentId, tf)} and writes the new bar. The TransactWrite path
     * is used when the existing-bar count is small enough to fit in one
     * transaction (≤ 25 items including the put); otherwise the implementation
     * falls back to non-atomic batched delete + put.
     */
    void snapshotReplace(String instrumentId, Timeframe tf, OHLCBar newBar, Optional<Long> ttl);

    /** Listing helper for tests / bulk recompute (CLAUDE.md §7). */
    List<OHLCBar> listAll(String instrumentId, Timeframe tf);
}
