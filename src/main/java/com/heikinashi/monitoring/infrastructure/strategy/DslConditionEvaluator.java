package com.heikinashi.monitoring.infrastructure.strategy;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.infrastructure.hatrack.CommonsBarAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.hatrack.dsl.BarIndicatorSource;
import org.hatrack.dsl.ExpressionEvaluator;
import org.hatrack.dsl.ExpressionRef;
import org.hatrack.dsl.NachtkrappMatchIndex;

/**
 * The dsl-eval seam (Blocks 15-16). Evaluates scenario condition strings via
 * {@link ExpressionEvaluator} over the instrument's <b>raw OHLC</b> series.
 *
 * <p><b>Why raw OHLC, not HA.</b> dsl-eval computes Heikin-Ashi <em>itself</em>
 * from the {@code OHLCBar} series it is given (via {@code HeikinAshiCalculator})
 * for the HA Tier-B primitives ({@code ha_doji}, {@code ha_bullish_reversal}…),
 * while Tier-A numeric indicators ({@code sma}/{@code rsi}/{@code macd}…) read the
 * same series' close. Feeding HA bars would compute HA twice. wichtelm-app feeds
 * raw OHLC; H-tchen does the same so live monitoring stays in semantic parity with
 * the backtest (same evaluator code, same inputs). H-tchen's HA series remains
 * only for the chart.
 *
 * <p><b>IndicatorSource.</b> dsl-eval's {@link BarIndicatorSource} serves Tier-A
 * indicators and resolves Tier-B/pivot booleans against a {@link NachtkrappMatchIndex}
 * pre-built by scanning the strategy's condition strings.
 *
 * <p><b>Values (stateless).</b> Only what an OHLC bar provides is supplied:
 * {@code open/high/low/close/volume} and {@code bar_index}. H-tchen is stateless
 * and supplies NO placeholder for anything else — {@code entry_price},
 * {@code position_size} or any unknown identifier throws, so a strategy needing
 * position state cannot be silently evaluated.
 */
final class DslConditionEvaluator {

    private final String source;
    private final String tfWire;
    private final List<org.hatrack.commons.OHLCBar> bars;
    private final NachtkrappMatchIndex matchIndex;

    DslConditionEvaluator(String strategyName, List<OHLCBar> ohlcAscending, Timeframe tf, List<String> allConditions) {
        this.source = strategyName;
        this.tfWire = tf.wire();
        this.bars = ohlcAscending.stream().map(CommonsBarAdapter::toCommons).toList();
        List<ExpressionRef> refs = allConditions.stream()
                .distinct()
                .map(text -> new ExpressionRef(text, tfWire))
                .toList();
        // Pre-index the Tier-B boolean primitives the conditions reference. No
        // parameters and no higher-timeframe series: H-tchen monitors one series.
        this.matchIndex = NachtkrappMatchIndex.buildFor(refs, Map.of(), bars, tfWire, Map.of());
    }

    /**
     * Evaluate a scenario's ANDed conditions at bar {@code index} using runtime
     * semantics: an indicator still warming up (insufficient history) means the
     * scenario simply cannot fire here, not an error. Out-of-range index yields
     * {@code false} (used for the previous bar at index 0).
     */
    boolean conjunctionHolds(List<String> conditions, int index) {
        if (index < 0 || index >= bars.size()) {
            return false;
        }
        ExpressionEvaluator.Scope current = scopeAt(index);
        ExpressionEvaluator.Scope previous = index > 0 ? scopeAt(index - 1) : null;
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator(source, bars.get(index).time(), index);
        for (String text : conditions) {
            boolean ok;
            try {
                ok = evaluator.condition(text, current, previous);
            } catch (org.hatrack.dsl.error.IndicatorWarmupException stillWarming) {
                return false;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluate a single condition at bar {@code index}, letting every exception
     * propagate (parse error / unsupported primitive → DslEvaluationException;
     * insufficient history → IndicatorWarmupException; unresolved identifier →
     * IllegalStateException). Used by the importer to fail loud.
     */
    void validateCondition(String text, int index) {
        ExpressionEvaluator.Scope current = scopeAt(index);
        ExpressionEvaluator.Scope previous = index > 0 ? scopeAt(index - 1) : null;
        new ExpressionEvaluator(source, bars.get(index).time(), index).condition(text, current, previous);
    }

    private ExpressionEvaluator.Scope scopeAt(int index) {
        List<org.hatrack.commons.OHLCBar> upTo = bars.subList(0, index + 1);
        org.hatrack.commons.OHLCBar bar = bars.get(index);
        BarIndicatorSource indicators = new BarIndicatorSource(upTo, source, bar.time(), index, matchIndex, tfWire);
        return new ExpressionEvaluator.Scope(values(bar, index), indicators);
    }

    private ExpressionEvaluator.Values values(org.hatrack.commons.OHLCBar bar, long index) {
        return name -> switch (name) {
            case "open" -> bar.open();
            case "high" -> bar.high();
            case "low" -> bar.low();
            case "close" -> bar.close();
            case "volume" -> bar.volume().orElse(BigDecimal.ZERO);
            case "bar_index" -> BigDecimal.valueOf(index);
            default ->
                throw new IllegalStateException("identifier '" + name
                        + "' cannot be supplied by H-tchen (stateless monitoring; no position state)");
        };
    }
}
