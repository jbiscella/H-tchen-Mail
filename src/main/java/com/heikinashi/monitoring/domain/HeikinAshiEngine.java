package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for Heikin Ashi computation (Block 12).
 *
 * <p>Block 12 moves the canonical HA formulas out of H-tchen's own
 * {@link HeikinAshiCalculator} and onto ha-track's shared
 * {@code org.hatrack.commons.HeikinAshiCalculator}, so H-tchen and wichtelm-app
 * compute Heikin Ashi identically. The application layer depends only on this
 * port; the commons-backed implementation lives in {@code infrastructure} (the
 * {@code domain} layer must never import {@code org.hatrack.*}).
 *
 * <p>{@link HeikinAshiCalculator} is retained as the canonical reference /
 * parity oracle — a test asserts the commons engine reproduces it bar-for-bar
 * (the cascade guard from CLAUDE.md Block 12).
 */
public interface HeikinAshiEngine {

    /**
     * Compute a chain of HA bars. {@code prev} is the HA bar immediately
     * preceding the first OHLC (empty for a fresh seed). {@code ohlcs} must be
     * sorted ascending by {@code bar_time}. The returned bars carry the same
     * {@code instrumentId}/{@code timeframe} as their source OHLC and the given
     * {@code computedAt}.
     */
    List<HABar> computeChain(Optional<HABar> prev, List<OHLCBar> ohlcs, Instant computedAt);
}
