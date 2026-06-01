package com.heikinashi.monitoring.infrastructure.strategy;

import com.heikinashi.monitoring.domain.strategy.MaKind;
import com.heikinashi.monitoring.domain.strategy.MarketCondition;
import com.heikinashi.monitoring.domain.strategy.PriceField;
import com.heikinashi.monitoring.domain.strategy.Side;
import org.hatrack.commons.PriceSource;
import org.hatrack.nachtkrapp.match.PatternMatch;
import org.hatrack.nachtkrapp.rule.DetectionRule;
import org.hatrack.nachtkrapp.rule.MAType;

/**
 * The single point that maps an H-tchen {@link MarketCondition} to a nachtkrapp
 * {@link DetectionRule} (Block 15) and correlates a resulting
 * {@link PatternMatch} back to the condition (Block 16). Every supported
 * condition is event-flavoured, so a match at a bar means the condition's
 * transition occurred on that bar.
 *
 * <p>Conditions that have no nachtkrapp equivalent never reach here: they are
 * rejected at JSON import ({@link StrategyJsonImporter}) — the one place that
 * fails loud on an unsupported condition.
 */
final class MarketConditionTranslator {

    private MarketConditionTranslator() {}

    /** The nachtkrapp rule that detects this condition. */
    static DetectionRule toRule(MarketCondition c) {
        return switch (c) {
            case MarketCondition.ColorChange cc -> new DetectionRule.HAColorChangeRule(cc.minStreakLength());
            case MarketCondition.StrongCandle sc ->
                new DetectionRule.HAStrongCandleRule(sc.wickTolerance(), sc.minBodyRatio());
            case MarketCondition.Doji d -> new DetectionRule.HADojiRule(d.maxBodyRatio());
            case MarketCondition.MovingAverageCross mac ->
                new DetectionRule.MACrossMARule(
                        maType(mac.fastType()),
                        mac.fastPeriod(),
                        maType(mac.slowType()),
                        mac.slowPeriod(),
                        priceSource(mac.source()));
            case MarketCondition.RsiMidlineCross r ->
                new DetectionRule.RSILevel50CrossRule(r.period(), priceSource(r.source()));
        };
    }

    /** Whether {@code match} is the event this condition looks for (type + direction + parameters). */
    static boolean corresponds(MarketCondition c, PatternMatch match) {
        return switch (c) {
            case MarketCondition.ColorChange cc ->
                switch (match) {
                    case PatternMatch.HABullishReversal m ->
                        cc.side() == Side.BULLISH && m.streakLength() >= cc.minStreakLength();
                    case PatternMatch.HABearishReversal m ->
                        cc.side() == Side.BEARISH && m.streakLength() >= cc.minStreakLength();
                    default -> false;
                };
            case MarketCondition.StrongCandle sc ->
                switch (match) {
                    case PatternMatch.HABullishStrong ignored -> sc.side() == Side.BULLISH;
                    case PatternMatch.HABearishStrong ignored -> sc.side() == Side.BEARISH;
                    default -> false;
                };
            case MarketCondition.Doji ignored -> match instanceof PatternMatch.HADoji;
            case MarketCondition.MovingAverageCross mac ->
                switch (match) {
                    case PatternMatch.MACrossedAboveMA m ->
                        mac.side() == Side.BULLISH
                                && maCrossMatches(mac, m.aType(), m.aPeriod(), m.bType(), m.bPeriod());
                    case PatternMatch.MACrossedBelowMA m ->
                        mac.side() == Side.BEARISH
                                && maCrossMatches(mac, m.aType(), m.aPeriod(), m.bType(), m.bPeriod());
                    default -> false;
                };
            case MarketCondition.RsiMidlineCross r ->
                switch (match) {
                    case PatternMatch.RSICrossedAbove50 m -> r.side() == Side.BULLISH && m.period() == r.period();
                    case PatternMatch.RSICrossedBelow50 m -> r.side() == Side.BEARISH && m.period() == r.period();
                    default -> false;
                };
        };
    }

    private static boolean maCrossMatches(
            MarketCondition.MovingAverageCross mac, MAType aType, int aPeriod, MAType bType, int bPeriod) {
        return aType == maType(mac.fastType())
                && aPeriod == mac.fastPeriod()
                && bType == maType(mac.slowType())
                && bPeriod == mac.slowPeriod();
    }

    private static MAType maType(MaKind kind) {
        return switch (kind) {
            case SMA -> MAType.SMA;
            case EMA -> MAType.EMA;
        };
    }

    private static PriceSource priceSource(PriceField field) {
        return switch (field) {
            case OPEN -> PriceSource.OPEN;
            case HIGH -> PriceSource.HIGH;
            case LOW -> PriceSource.LOW;
            case CLOSE -> PriceSource.CLOSE;
            case HA_OPEN -> PriceSource.HA_OPEN;
            case HA_HIGH -> PriceSource.HA_HIGH;
            case HA_LOW -> PriceSource.HA_LOW;
            case HA_CLOSE -> PriceSource.HA_CLOSE;
        };
    }
}
