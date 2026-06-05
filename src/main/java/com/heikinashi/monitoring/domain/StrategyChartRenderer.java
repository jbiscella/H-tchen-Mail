package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import java.util.List;

/**
 * Port for rendering the inline chart attached to a strategy alert email
 * (CLAUDE.md §9 Component 1b, SI-3c.1). Distinct from {@link ChartRenderer}
 * (which renders a legacy {@code PatternEvent}): a strategy alert's chart is
 * built from the strategy's derived overlays and a role-direction marker, so the
 * renderer needs the {@link Strategy} (for overlay derivation), the
 * {@link StrategyAlert} (for the trigger bar + roles), and the HA lookback bars.
 *
 * <p>Implementations may raise
 * {@link com.heikinashi.monitoring.domain.error.ChartRenderException} on failure.
 */
public interface StrategyChartRenderer {

    ChartImage render(StrategyAlert alert, Strategy strategy, List<HABar> bars);
}
