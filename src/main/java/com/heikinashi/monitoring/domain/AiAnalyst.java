package com.heikinashi.monitoring.domain;

/**
 * Port for the Bedrock-backed AI analyst (CLAUDE.md §9). Implementations may
 * raise {@link com.heikinashi.monitoring.domain.error.LLMException} on
 * model / loop / parse failures.
 */
public interface AiAnalyst {
    AiAnalysis analyze(PatternEvent event);

    /**
     * Analyze a strategy alert (CLAUDE.md §9 Component 1c). The prompt describes
     * the matched scenarios / roles instead of a single pattern; the tool-use
     * loop and output schema are otherwise identical to {@link #analyze(PatternEvent)}.
     *
     * <p><b>The caller passes both artefacts it rendered the attached chart from</b> —
     * the {@code Strategy} and the bar window — so the note's technical context
     * describes what the reader is actually looking at. Re-deriving either inside the
     * analyst reopens a divergence (Block 18 / Codex review of PR #86):
     *
     * <ul>
     *   <li>a re-import between dispatch and this call can swap the strategy behind an
     *       instrument id, so the note would list overlays the chart never drew;
     *   <li>the bar list is <i>repaired</i> upstream in ways a fresh read cannot
     *       reproduce — {@code MonitoringRunService} merges freshly-ingested bars over
     *       a repository read that may lag its own write, and
     *       {@code StrategyRetryPollerService} splices back a trigger bar that
     *       retention evicted before the retry ran.
     * </ul>
     */
    AiAnalysis analyze(
            com.heikinashi.monitoring.domain.strategy.StrategyAlert alert,
            com.heikinashi.monitoring.domain.strategy.Strategy strategy,
            java.util.List<OHLCBar> bars);

    /**
     * Degraded variant for the retry path when the strategy has been deleted since
     * detection — the send is already chart-degraded in that case. The note carries
     * the bar series with no indicator values, since the indicator set is a property
     * of the strategy. {@code Optional} is deliberately not used as a parameter (§13).
     */
    AiAnalysis analyze(
            com.heikinashi.monitoring.domain.strategy.StrategyAlert alert, java.util.List<OHLCBar> bars);
}
