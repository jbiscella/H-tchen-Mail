package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.StrategyChartRenderer;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Test fake. Returns a fixed PNG by default; can be scripted to fail next N calls. */
public final class ScriptedStrategyChartRenderer implements StrategyChartRenderer {

    private final Deque<RuntimeException> scriptedFailures = new ArrayDeque<>();
    private int callCount;

    public void failNext(int n) {
        for (int i = 0; i < n; i++) {
            scriptedFailures.add(new ChartRenderException(new RuntimeException("scripted strategy chart failure")));
        }
    }

    public int callCount() {
        return callCount;
    }

    @Override
    public ChartImage render(StrategyAlert alert, Strategy strategy, List<HABar> bars) {
        callCount++;
        RuntimeException next = scriptedFailures.pollFirst();
        if (next != null) {
            throw next;
        }
        return new ChartImage(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png", 900, 500);
    }
}
