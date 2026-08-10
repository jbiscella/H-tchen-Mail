package com.heikinashi.monitoring.infrastructure.chart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.hatrack.heerwisch.api.spec.Pane;

/**
 * Which of a resolved indicator list a chart actually <em>draws</em>, and in which pane.
 *
 * <p>Three rules decide that, and none of them is optional:
 *
 * <ol>
 *   <li>an indicator whose {@link Indicator#minBars()} exceeds the available window is
 *       skipped — heerwisch rejects those with V6, and a short bootstrap chart should
 *       still render with fewer overlays. The comparison is strictly-less, so a window
 *       exactly equal to {@code minBars} draws;
 *   <li>duplicates collapse by indicator identity;
 *   <li>{@link Pane#MAIN} indicators overlay in place, while the rest cycle a
 *       <b>fixed set of eight subplot slots</b> — a ninth oscillator is silently not
 *       drawn, because there is nowhere left to put it.
 * </ol>
 *
 * <p>This loop was duplicated verbatim in {@link HeerwischChartRenderer} and
 * {@link StrategyChartSpec}. Block 18 added a third consumer — the AI analyst's
 * technical-context block, which must describe exactly the overlays the reader can see —
 * and three copies of a rule is three chances to drift. A Codex review of PR #86 caught
 * exactly that: the context builder had reimplemented rule 1 and silently omitted rules 2
 * and 3, so a strategy referencing nine distinct oscillator periods would have produced a
 * note describing sub-panes absent from the attached image.
 *
 * <p>Adding a subplot slot therefore changes the drawn set and the described set together,
 * by construction rather than by discipline.
 */
public final class ChartIndicatorPlacement {

    private static final Pane[] SUB_PANES = {
        Pane.SUBPLOT_1, Pane.SUBPLOT_2, Pane.SUBPLOT_3, Pane.SUBPLOT_4,
        Pane.SUBPLOT_5, Pane.SUBPLOT_6, Pane.SUBPLOT_7, Pane.SUBPLOT_8
    };

    private ChartIndicatorPlacement() {}

    /**
     * One drawn indicator and the pane it goes in. {@code pane} is {@link Pane#MAIN} for
     * overlays; callers add those without an explicit pane so heerwisch keeps applying
     * {@code defaultPane()}.
     */
    public record Placed(Indicator indicator, Pane pane) {}

    /**
     * The indicators drawn for a window of {@code bars} bars, in draw order.
     *
     * @param indicators the resolved set, from {@link ConfiguredChartIndicators#derive} or
     *     {@link StrategyChartIndicators#derive}
     * @param bars the number of bars available to the chart — the same count the caller
     *     passes to heerwisch, so the V6 skip decision matches
     */
    public static List<Placed> drawn(List<Indicator> indicators, int bars) {
        List<Placed> placed = new ArrayList<>(indicators.size());
        Set<String> seen = new HashSet<>();
        int subPaneIdx = 0;
        for (Indicator indicator : indicators) {
            if (bars < indicator.minBars() || !seen.add(indicator.toString())) {
                continue;
            }
            if (indicator.defaultPane() == Pane.MAIN) {
                placed.add(new Placed(indicator, Pane.MAIN));
            } else if (subPaneIdx < SUB_PANES.length) {
                placed.add(new Placed(indicator, SUB_PANES[subPaneIdx]));
                subPaneIdx++;
            }
        }
        return List.copyOf(placed);
    }
}
