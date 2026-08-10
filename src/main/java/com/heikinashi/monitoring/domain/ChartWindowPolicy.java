package com.heikinashi.monitoring.domain;

/**
 * How many bars the alert chart draws — the one number the application layer needs in order
 * to resolve a pattern alert's bar window itself (Block 18).
 *
 * <p>The window has to be resolved by the <em>caller</em>, so that the chart renderer and the
 * AI analyst receive the identical list rather than each reading the store; see the Block 18
 * invariant in CLAUDE.md. But the value is operator-configurable under
 * {@code monitoring.chart}, and no {@code application} class may import
 * {@code infrastructure}. Hence this abstraction: named where it is consumed, with
 * {@code ChartConfig} as the single implementation — the same shape as
 * {@code RecencyWindowSource}, which {@code MarketauxConfig} implements for the news window.
 *
 * <p>The alternative — a second hardcoded copy of the default in the application layer —
 * would silently ignore {@code monitoring.chart.lookback-bars} and let the note describe a
 * different number of bars than the chart draws, which is the exact failure this block exists
 * to prevent.
 */
public interface ChartWindowPolicy {

    /** Bars drawn per alert chart, ending at the alert bar. */
    int lookbackBars();
}
