package com.heikinashi.monitoring.domain.strategy;

import java.util.Objects;
import java.util.Optional;

/**
 * One matched scenario rendered for the alert email (Block 16). The role label
 * and any stop-loss / take-profit / position-precondition text are quoted
 * verbatim from the source — never evaluated.
 */
public record StrategyAlertLine(
        String scenarioName,
        String role,
        Optional<String> positionPrecondition,
        Optional<String> stopLoss,
        Optional<String> takeProfit) {

    public StrategyAlertLine {
        Objects.requireNonNull(scenarioName, "scenarioName");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(positionPrecondition, "positionPrecondition");
        Objects.requireNonNull(stopLoss, "stopLoss");
        Objects.requireNonNull(takeProfit, "takeProfit");
    }

    public static StrategyAlertLine from(StrategyScenario scenario) {
        return new StrategyAlertLine(
                scenario.name(),
                scenario.role(),
                scenario.positionPrecondition(),
                scenario.stopLoss(),
                scenario.takeProfit());
    }
}
