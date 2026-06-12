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
     */
    AiAnalysis analyze(com.heikinashi.monitoring.domain.strategy.StrategyAlert alert);
}
