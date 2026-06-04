package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;

/**
 * Block 16 — renders a {@link StrategyAlert} as the stateless monitoring note.
 * One alert (hence one email) carries one line per scenario that became true on
 * the latest bar. Each line shows the role label verbatim; any stop-loss /
 * take-profit / position precondition is quoted exactly as written and annotated
 * as the user's responsibility — H-tchen never evaluates them, never computes an
 * absolute price from them, and never tracks whether a position is open.
 */
public final class StrategyAlertText {

    private static final String BROKER_NOTE = "(set this at your broker — H-tchen never places orders)";

    private StrategyAlertText() {}

    public static String render(StrategyAlert alert) {
        StringBuilder sb = new StringBuilder();
        sb.append("Strategy alert: ").append(alert.strategyName()).append('\n');
        sb.append("Instrument:   ")
                .append(alert.ticker())
                .append(" on ")
                .append(alert.exchange())
                .append('\n');
        sb.append("Timeframe:    ").append(alert.timeframe().wire()).append('\n');
        sb.append("Bar time:     ").append(alert.barTime()).append('\n');
        sb.append('\n');
        sb.append("Matched scenarios:").append('\n');
        for (StrategyAlertLine line : alert.lines()) {
            sb.append("  - ")
                    .append(line.scenarioName())
                    .append(" [")
                    .append(line.role())
                    .append("]\n");
            line.positionPrecondition().ifPresent(p -> sb.append("      Applies only if: ")
                    .append(p)
                    .append("  (context — H-tchen does not check your position)\n"));
            line.stopLoss().ifPresent(sl -> sb.append("      Stop loss:   ")
                    .append(sl)
                    .append("  ")
                    .append(BROKER_NOTE)
                    .append('\n'));
            line.takeProfit().ifPresent(tp -> sb.append("      Take profit: ")
                    .append(tp)
                    .append("  ")
                    .append(BROKER_NOTE)
                    .append('\n'));
        }
        return sb.toString();
    }
}
