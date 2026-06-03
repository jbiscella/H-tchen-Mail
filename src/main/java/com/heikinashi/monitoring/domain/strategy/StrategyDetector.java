package com.heikinashi.monitoring.domain.strategy;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for evaluating an imported {@link Strategy} against the latest bar
 * (Blocks 15-16). Pure: the only input is the OHLC series the caller already
 * read; the detector performs no I/O. Deterministic: the same series + strategy
 * always yields the same alert.
 *
 * <p>The series is the instrument's <b>raw OHLC</b> (not Heikin-Ashi): dsl-eval
 * computes Heikin-Ashi itself for the HA primitives, matching wichtelm-app, so
 * live monitoring stays in parity with the backtest. The HA series is for the
 * chart only.
 *
 * <p>Returns at most one {@link StrategyAlert} (so at most one email) carrying
 * one line per scenario that became true on the latest bar.
 */
public interface StrategyDetector {

    Optional<StrategyAlert> evaluateLatest(
            Instrument instrument, Timeframe tf, Strategy strategy, List<OHLCBar> ohlcSeries, Instant detectedAt);
}
