package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.OHLCBar;
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
    private List<OHLCBar> lastBars = List.of();
    private StrategyAlert lastAlert;

    public void failNext(int n) {
        for (int i = 0; i < n; i++) {
            scriptedFailures.add(new ChartRenderException(new RuntimeException("scripted strategy chart failure")));
        }
    }

    public int callCount() {
        return callCount;
    }

    /** The bar series passed to the most recent render call (for retry bar-restore assertions). */
    public List<OHLCBar> lastBars() {
        return lastBars;
    }

    /** The alert passed to the most recent render call (for forced-line / routing assertions). */
    public StrategyAlert lastAlert() {
        return lastAlert;
    }

    @Override
    public ChartImage render(StrategyAlert alert, Strategy strategy, List<OHLCBar> bars) {
        callCount++;
        lastBars = List.copyOf(bars);
        lastAlert = alert;
        RuntimeException next = scriptedFailures.pollFirst();
        if (next != null) {
            throw next;
        }
        return new ChartImage(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png", 900, 500);
    }
}
