package com.heikinashi.monitoring.infrastructure.strategy;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.strategy.MarketCondition;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import com.heikinashi.monitoring.domain.strategy.StrategyDetector;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import com.heikinashi.monitoring.infrastructure.hatrack.CommonsBarAdapter;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hatrack.nachtkrapp.detector.DetectionResult;
import org.hatrack.nachtkrapp.detector.PatternDetector;
import org.hatrack.nachtkrapp.detector.RuleBasedPatternDetector;
import org.hatrack.nachtkrapp.error.DetectionException;
import org.hatrack.nachtkrapp.error.InsufficientDataException;
import org.hatrack.nachtkrapp.match.PatternMatch;
import org.hatrack.nachtkrapp.rule.DetectionRule;
import org.hatrack.nachtkrapp.spec.DetectionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block 15-16 — {@link StrategyDetector} backed by nachtkrapp's
 * {@link org.hatrack.nachtkrapp.detector.PatternDetector}. Builds one
 * {@link DetectionSpec} from every scenario's conditions (a single
 * {@code detect} call), then evaluates each scenario against the latest bar.
 *
 * <p>All supported conditions are event-flavoured, so a scenario "became true on
 * the latest bar" exactly when every one of its conditions has a corresponding
 * match at the latest bar time — which is per-scenario transition gating without
 * extra state (Block 16). When a condition needs more bars than the series holds
 * (e.g. a long-period cross during a short first ingest), its rule is skipped and
 * the scenario simply does not fire; nachtkrapp's {@link InsufficientDataException}
 * is also caught defensively.
 */
@Singleton
public class NachtkrappStrategyDetector implements StrategyDetector {

    private static final Logger LOG = LoggerFactory.getLogger(NachtkrappStrategyDetector.class);

    private final PatternDetector detector = new RuleBasedPatternDetector();

    @Override
    public Optional<StrategyAlert> evaluateLatest(
            Instrument instrument, Timeframe tf, Strategy strategy, List<HABar> haSeries, Instant detectedAt) {
        if (haSeries.isEmpty()) {
            return Optional.empty();
        }
        List<HABar> sorted = new ArrayList<>(haSeries);
        sorted.sort(Comparator.comparing(HABar::barTime));
        Instant latest = sorted.get(sorted.size() - 1).barTime();
        int size = sorted.size();

        // Collect the distinct, runnable rules across all scenarios (dedup avoids
        // nachtkrapp's duplicate-rule rejection; minBars filtering avoids its
        // insufficient-data rejection — an unsatisfiable condition just can't fire).
        Set<DetectionRule> rules = new LinkedHashSet<>();
        for (StrategyScenario scenario : strategy.scenarios()) {
            for (MarketCondition condition : scenario.conditions()) {
                DetectionRule rule = MarketConditionTranslator.toRule(condition);
                if (rule.minBars() <= size) {
                    rules.add(rule);
                }
            }
        }
        if (rules.isEmpty()) {
            return Optional.empty();
        }

        List<PatternMatch> latestMatches;
        try {
            DetectionSpec spec = DetectionSpec.builder()
                    .withSeries(CommonsBarAdapter.toCommonsHaSeries(sorted))
                    .withTimeframe(CommonsBarAdapter.toCommons(tf))
                    .addAllRules(rules)
                    .build();
            DetectionResult result = detector.detect(spec);
            latestMatches = result.matches().stream()
                    .filter(m -> m.time().equals(latest))
                    .toList();
        } catch (InsufficientDataException e) {
            return Optional.empty();
        } catch (DetectionException e) {
            // Pre-validation makes the other spec violations unreachable; surface
            // any genuine internal failure rather than silently dropping alerts.
            throw new IllegalStateException("nachtkrapp detection failed", e);
        }

        List<StrategyAlertLine> lines = new ArrayList<>();
        for (StrategyScenario scenario : strategy.scenarios()) {
            if (scenarioMatchedLatest(scenario, size, latestMatches)) {
                lines.add(StrategyAlertLine.from(scenario));
            }
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
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

    @Override
    public int barsNeeded(Strategy strategy) {
        int max = 1;
        for (StrategyScenario scenario : strategy.scenarios()) {
            for (MarketCondition condition : scenario.conditions()) {
                max = Math.max(max, MarketConditionTranslator.toRule(condition).minBars());
            }
        }
        return max;
    }

    private static boolean scenarioMatchedLatest(
            StrategyScenario scenario, int seriesSize, List<PatternMatch> latestMatches) {
        for (MarketCondition condition : scenario.conditions()) {
            // A condition needing more bars than we have can never be satisfied.
            if (MarketConditionTranslator.toRule(condition).minBars() > seriesSize) {
                return false;
            }
            boolean satisfied =
                    latestMatches.stream().anyMatch(m -> MarketConditionTranslator.corresponds(condition, m));
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }
}
