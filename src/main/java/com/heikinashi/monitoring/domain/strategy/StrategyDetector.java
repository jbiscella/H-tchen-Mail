package com.heikinashi.monitoring.domain.strategy;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.Timeframe;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for evaluating an imported {@link Strategy} against the latest HA bar
 * (Blocks 15-16). Pure: the only input is the HA series the caller already read;
 * the detector performs no I/O. Deterministic: the same series + strategy always
 * yields the same alert.
 *
 * <p>Returns at most one {@link StrategyAlert} (so at most one email) carrying
 * one line per scenario that became true on the latest bar.
 */
public interface StrategyDetector {

    Optional<StrategyAlert> evaluateLatest(
            Instrument instrument, Timeframe tf, Strategy strategy, List<HABar> haSeries, Instant detectedAt);

    /**
     * The most bars any of the strategy's conditions needs to produce a match —
     * the HA history window the caller must supply so the latest bar has its full
     * lookback.
     */
    int barsNeeded(Strategy strategy);
}
