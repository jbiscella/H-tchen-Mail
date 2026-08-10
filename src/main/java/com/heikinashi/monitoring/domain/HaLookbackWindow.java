package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The HA lookback window for a pattern alert, shared by the chart renderer and the AI
 * analyst's technical-context block so the two cannot disagree about which bars exist.
 *
 * <p>Lives in {@code domain}: it only ever touched domain types, and it sat in
 * {@code infrastructure.chart} purely because it was extracted from the renderer. Moving it
 * lets the application services resolve the window themselves without importing
 * infrastructure (Block 18 invariant: the caller resolves once, both consumers receive the
 * same list).
 *
 * <p>Extracted from {@code HeerwischChartRenderer.fetchLookback} for Block 18. The repair
 * below is the reason it must be shared rather than reimplemented: under
 * {@code SNAPSHOT_ONLY} retention a later ingest can delete the triggering HA bar before a
 * queued alert is retried, so the stored window may no longer contain
 * {@code event.barTime()}. The chart repairs that from the event's own snapshot (heerwisch
 * V7 requires the {@code BarHighlight} to land on a real bar). Without the same repair the
 * emailed chart would show the triggering candle while the note omitted it and could not
 * report alert-bar indicator values.
 */
public final class HaLookbackWindow {

    private HaLookbackWindow() {}

    /**
     * The last {@code lookbackBars} HA bars up to and including {@code event.barTime()},
     * ascending, with the triggering bar synthesized from the event snapshot when
     * retention has already removed it.
     */
    public static List<HABar> forEvent(HaRepository haRepository, PatternEvent event, int lookbackBars) {
        Instant cutoff = event.barTime().plusNanos(1);
        List<HABar> bars = new ArrayList<>(
                haRepository.findLastNBefore(event.instrumentId(), event.timeframe(), cutoff, lookbackBars));
        boolean hasEventBar = bars.stream().anyMatch(b -> b.barTime().equals(event.barTime()));
        if (!hasEventBar) {
            bars.add(new HABar(
                    event.instrumentId(),
                    event.timeframe(),
                    event.barTime(),
                    event.barSnapshot().haOpen(),
                    event.barSnapshot().haHigh(),
                    event.barSnapshot().haLow(),
                    event.barSnapshot().haClose(),
                    event.detectedAt()));
        }
        // heerwisch requires strictly-ascending, unique bar times (V3/V4).
        bars.sort(Comparator.comparing(HABar::barTime));
        return bars;
    }
}
