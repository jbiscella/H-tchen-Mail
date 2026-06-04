package com.heikinashi.monitoring.infrastructure.hatrack;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HeikinAshiEngine;
import com.heikinashi.monitoring.domain.OHLCBar;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Block 12 — {@link HeikinAshiEngine} backed by ha-track's commons calculator
 * ({@code org.hatrack.commons.HeikinAshiCalculator}). All bar-type conversion
 * goes through {@link CommonsBarAdapter} (the single domain&lt;-&gt;commons boundary).
 *
 * <p>The persisted HA schema is unchanged: this swaps the calculator, not the
 * storage model. Commons HA bars carry only {@code time + ha prices}; the
 * denormalized {@code instrumentId}/{@code timeframe} are re-attached from the
 * matching source OHLC (same order, same {@code bar_time}), and {@code computedAt}
 * from the caller.
 */
@Singleton
public class CommonsHeikinAshiEngine implements HeikinAshiEngine {

    @Override
    public List<HABar> computeChain(Optional<HABar> prev, List<OHLCBar> ohlcs, Instant computedAt) {
        if (ohlcs.isEmpty()) {
            return List.of();
        }

        Optional<org.hatrack.commons.HABar> prevCommons = prev.map(CommonsBarAdapter::toCommons);
        List<org.hatrack.commons.OHLCBar> ohlcCommons =
                ohlcs.stream().map(CommonsBarAdapter::toCommons).toList();

        List<org.hatrack.commons.HABar> computed =
                org.hatrack.commons.HeikinAshiCalculator.computeChain(prevCommons, ohlcCommons);

        // computeChain preserves order and count 1:1 with the input OHLC chain.
        List<HABar> out = new ArrayList<>(computed.size());
        for (int i = 0; i < computed.size(); i++) {
            OHLCBar source = ohlcs.get(i);
            out.add(CommonsBarAdapter.fromCommons(
                    computed.get(i), source.instrumentId(), source.timeframe(), computedAt));
        }
        return out;
    }
}
