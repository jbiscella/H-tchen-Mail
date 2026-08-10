package com.heikinashi.monitoring.infrastructure.chart;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hatrack.commons.PriceSource;
import org.hatrack.heerwisch.api.spec.Indicator;

/**
 * The indicator set a pattern-alert chart draws, resolved from {@code monitoring.chart}
 * configuration. The strategy-alert counterpart is
 * {@link StrategyChartIndicators#derive(com.heikinashi.monitoring.domain.strategy.Strategy)},
 * which resolves the set from the strategy's referenced indicators instead.
 *
 * <p>Extracted from {@link HeerwischChartRenderer} in Block 18 so the renderer and the AI
 * analyst's technical-context block resolve the set through the <em>same</em> call. The
 * point is not code reuse for its own sake: if the two derived their lists independently
 * they could drift, and the alert note would describe an indicator the reader cannot see
 * in the image beside it — or miss one they can.
 *
 * <p>An indicator is included only when the operator enabled it: a period of 0 disables
 * the moving averages, and {@code show-rsi} gates the RSI sub-pane. Callers must still
 * apply the window check ({@link Indicator#minBars()}), which the renderer needs anyway
 * to satisfy heerwisch's V6.
 */
public final class ConfiguredChartIndicators {

    /** HA charts read the Heikin-Ashi close (wichtelm charts raw OHLC and uses CLOSE). */
    static final PriceSource SOURCE = PriceSource.HA_CLOSE;

    private static final BigDecimal RSI_OVERBOUGHT = new BigDecimal("70");
    private static final BigDecimal RSI_OVERSOLD = new BigDecimal("30");

    private ConfiguredChartIndicators() {}

    /** The configured indicators, in the order the chart collects them. */
    public static List<Indicator> derive(ChartConfig config) {
        List<Indicator> indicators = new ArrayList<>();
        if (config.getSmaPeriod() > 0) {
            indicators.add(new Indicator.SMA(config.getSmaPeriod(), SOURCE));
        }
        if (config.getEmaPeriod() > 0) {
            indicators.add(new Indicator.EMA(config.getEmaPeriod(), SOURCE));
        }
        if (config.isShowRsi()) {
            indicators.add(new Indicator.RSI(
                    config.getRsiPeriod(),
                    RSI_OVERBOUGHT,
                    RSI_OVERSOLD,
                    SOURCE,
                    Optional.of(Indicator.RsiVisualization.DANGER_ZONES_ON)));
        }
        return indicators;
    }
}
