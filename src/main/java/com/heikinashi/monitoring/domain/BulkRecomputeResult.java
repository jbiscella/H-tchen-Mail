package com.heikinashi.monitoring.domain;

/** Result of a bulk HA recompute (CLAUDE.md §7). */
public record BulkRecomputeResult(int ohlcCount, int haCount, long durationMs) {}
