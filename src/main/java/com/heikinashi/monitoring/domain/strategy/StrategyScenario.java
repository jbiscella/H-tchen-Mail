package com.heikinashi.monitoring.domain.strategy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One scenario of a strategy (Blocks 15-16). All {@code conditions} are market
 * conditions H-tchen evaluates and are ANDed: the scenario is true on a bar only
 * when every condition is true on that bar.
 *
 * <p>{@code role} is a free-text display label (e.g. "long_entry", "long_exit") —
 * shown verbatim, never branched on. {@code positionPrecondition},
 * {@code stopLoss} and {@code takeProfit} are verbatim memo text: H-tchen never
 * evaluates them (the user configures stops at their broker; H-tchen is stateless
 * and never tracks whether a position is open).
 */
public record StrategyScenario(
        String name,
        String role,
        List<MarketCondition> conditions,
        Optional<String> positionPrecondition,
        Optional<String> stopLoss,
        Optional<String> takeProfit) {

    public StrategyScenario {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        conditions = List.copyOf(conditions);
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("scenario '" + name + "' must have at least one condition");
        }
        Objects.requireNonNull(positionPrecondition, "positionPrecondition");
        Objects.requireNonNull(stopLoss, "stopLoss");
        Objects.requireNonNull(takeProfit, "takeProfit");
    }
}
