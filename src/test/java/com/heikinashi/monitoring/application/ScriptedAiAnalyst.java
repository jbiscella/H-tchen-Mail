package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.AiConfidence;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.error.LLMException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** Test fake. Returns a canned analysis by default; can be scripted to fail next N calls. */
public final class ScriptedAiAnalyst implements AiAnalyst {

    private final Deque<RuntimeException> scriptedFailures = new ArrayDeque<>();
    private int callCount;

    public void failNext(int n) {
        for (int i = 0; i < n; i++) {
            scriptedFailures.add(new LLMException("scripted LLM failure"));
        }
    }

    public int callCount() {
        return callCount;
    }

    @Override
    public AiAnalysis analyze(PatternEvent event) {
        return nextAnalysis();
    }

    @Override
    public AiAnalysis analyze(
            com.heikinashi.monitoring.domain.strategy.StrategyAlert alert,
            com.heikinashi.monitoring.domain.strategy.Strategy strategy,
            java.util.List<com.heikinashi.monitoring.domain.OHLCBar> bars) {
        // Block 18: callers pass the strategy and the bar window the chart was drawn from;
        // the scripted double answers identically either way.
        return nextAnalysis();
    }

    @Override
    public AiAnalysis analyze(
            com.heikinashi.monitoring.domain.strategy.StrategyAlert alert,
            java.util.List<com.heikinashi.monitoring.domain.OHLCBar> bars) {
        return nextAnalysis();
    }

    private AiAnalysis nextAnalysis() {
        callCount++;
        RuntimeException next = scriptedFailures.pollFirst();
        if (next != null) {
            throw next;
        }
        return new AiAnalysis(
                Optional.of("Earnings momentum and recent positive news flow corroborate the signal."),
                Optional.of("Sector beta and recent insider sales suggest caution."),
                AiConfidence.MEDIUM,
                List.of("quote_info", "news"));
    }
}
