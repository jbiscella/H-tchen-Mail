package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.ChartRenderer;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.error.ChartRenderException;
import java.util.ArrayDeque;
import java.util.Deque;

/** Test fake. Returns a fixed image by default; can be scripted to fail next N calls. */
public final class ScriptedChartRenderer implements ChartRenderer {

    private final Deque<RuntimeException> scriptedFailures = new ArrayDeque<>();
    private int callCount;

    public void failNext(int n) {
        for (int i = 0; i < n; i++) {
            scriptedFailures.add(new ChartRenderException(new RuntimeException("scripted chart failure")));
        }
    }

    public int callCount() {
        return callCount;
    }

    @Override
    public ChartImage renderChart(PatternEvent event) {
        callCount++;
        RuntimeException next = scriptedFailures.pollFirst();
        if (next != null) {
            throw next;
        }
        return new ChartImage(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png", 900, 500);
    }
}
