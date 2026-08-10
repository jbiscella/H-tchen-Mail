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
     * <p>The caller passes the {@code Strategy} it rendered the attached chart from,
     * so the note's technical context describes the same strategy the reader sees
     * charted. Looking the strategy up again inside the analyst would allow a
     * re-import between dispatch and this call to swap it (Block 18 / Codex review
     * of PR #86).
     */
    AiAnalysis analyze(
            com.heikinashi.monitoring.domain.strategy.StrategyAlert alert,
            com.heikinashi.monitoring.domain.strategy.Strategy strategy);

    /**
     * Degraded variant for the retry path when the strategy has been deleted since
     * detection — the send is already chart-degraded in that case. The note carries
     * the bar series with no indicator values, since the indicator set is a property
     * of the strategy. {@code Optional} is deliberately not used as a parameter (§13).
     */
    AiAnalysis analyze(com.heikinashi.monitoring.domain.strategy.StrategyAlert alert);
}
