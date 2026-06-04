package com.heikinashi.monitoring.domain.strategy;

import java.util.List;
import java.util.Objects;

/**
 * An imported strategy monitored for an instrument (Blocks 15-16). A strategy
 * supersedes the three fixed patterns for its instrument: an instrument
 * monitored by a strategy no longer emits the legacy color_change /
 * strong_candle / doji alerts — only the strategy's scenarios can raise alerts.
 */
public record Strategy(String name, List<StrategyScenario> scenarios) {

    public Strategy {
        Objects.requireNonNull(name, "name");
        scenarios = List.copyOf(scenarios);
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("strategy '" + name + "' must have at least one scenario");
        }
    }
}
