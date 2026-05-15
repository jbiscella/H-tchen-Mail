package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.PatternEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps only the events tied to the most-recent {@code bar_time} in a list
 * of detector outputs.
 *
 * <p>The detector itself is pure and emits one event per matching
 * (bar, pattern) — on a bootstrap run, where the freshly computed HA
 * chain spans hundreds of historical bars, that can mean dozens of
 * stale alerts. This filter collapses the result to the chronologically
 * last bar so operators only get notified about the latest signal per
 * (instrument, timeframe). Multiple patterns on that same latest bar
 * (e.g. {@code color_change} + {@code strong_candle}) are all preserved
 * — only older bars get dropped.
 *
 * <p>Trade-off: catch-up runs after a Lambda outage will lose patterns
 * that fired on intermediate bars. Acceptable today; can be relaxed
 * later via a flag on {@link com.heikinashi.monitoring.domain.MainInput}
 * if catch-up emission becomes a real need.
 */
final class LatestBarEventFilter {

    private LatestBarEventFilter() {}

    static List<PatternEvent> keepLatestBarOnly(List<PatternEvent> events) {
        if (events.isEmpty()) return events;
        Instant max = events.get(0).barTime();
        for (PatternEvent e : events) {
            if (e.barTime().isAfter(max)) max = e.barTime();
        }
        List<PatternEvent> out = new ArrayList<>(events.size());
        for (PatternEvent e : events) {
            if (e.barTime().equals(max)) out.add(e);
        }
        return out;
    }
}
