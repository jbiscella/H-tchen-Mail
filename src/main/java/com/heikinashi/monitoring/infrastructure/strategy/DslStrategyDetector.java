package com.heikinashi.monitoring.infrastructure.strategy;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import com.heikinashi.monitoring.domain.strategy.StrategyDetector;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocks 15-16 — {@link StrategyDetector} backed by ha-track's {@code dsl-eval}.
 * Each scenario's conditions are dsl-eval expression strings (full DSL vocabulary,
 * not a fixed set of typed conditions), evaluated over the raw OHLC series by
 * {@link DslConditionEvaluator}.
 *
 * <p>Per-scenario transition gating (Block 16): a scenario fires only when its
 * ANDed conditions go false→true on the latest bar — evaluated by comparing the
 * conjunction at the latest bar with the conjunction at the previous bar. Each
 * scenario's edge is independent. Multiple matched scenarios become one alert
 * (one email) with one line each.
 *
 * <p>Strategies are validated against real OHLC bars at import (parse errors,
 * unsupported primitives and insufficient history are rejected there), so at run
 * time a still-warming-up indicator simply means a scenario cannot fire yet.
 */
@Singleton
public class DslStrategyDetector implements StrategyDetector {

    private static final Logger LOG = LoggerFactory.getLogger(DslStrategyDetector.class);

    @Override
    public Optional<StrategyAlert> evaluateLatest(
            Instrument instrument, Timeframe tf, Strategy strategy, List<OHLCBar> ohlcSeries, Instant detectedAt) {
        if (ohlcSeries.isEmpty()) {
            return Optional.empty();
        }
        List<OHLCBar> sorted = new ArrayList<>(ohlcSeries);
        sorted.sort(Comparator.comparing(OHLCBar::barTime));
        int last = sorted.size() - 1;

        List<String> allConditions = strategy.scenarios().stream()
                .flatMap(s -> s.conditions().stream())
                .toList();
        DslConditionEvaluator evaluator = new DslConditionEvaluator(strategy.name(), sorted, tf, allConditions);

        List<StrategyAlertLine> lines = new ArrayList<>();
        for (StrategyScenario scenario : strategy.scenarios()) {
            boolean holdsNow = evaluator.conjunctionHolds(scenario.conditions(), last);
            boolean holdsPrev = evaluator.conjunctionHolds(scenario.conditions(), last - 1);
            if (holdsNow && !holdsPrev) {
                lines.add(StrategyAlertLine.from(scenario));
            }
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        Instant latest = sorted.get(last).barTime();
        LOG.info(
                "strategy_alert instrument_id={} timeframe={} bar_time={} strategy={} matched_scenarios={}",
                instrument.id(),
                tf.wire(),
                latest,
                strategy.name(),
                lines.size());
        return Optional.of(new StrategyAlert(
                instrument.id(),
                instrument.ticker(),
                instrument.exchange(),
                tf,
                latest,
                strategy.name(),
                lines,
                detectedAt));
    }
}
