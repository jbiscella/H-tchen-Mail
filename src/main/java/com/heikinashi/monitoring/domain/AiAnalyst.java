package com.heikinashi.monitoring.domain;

/**
 * Port for the Bedrock-backed AI analyst (CLAUDE.md §9). Implementations may
 * raise {@link com.heikinashi.monitoring.domain.error.LLMException} on
 * model / loop / parse failures.
 */
public interface AiAnalyst {
    AiAnalysis analyze(PatternEvent event);
}
