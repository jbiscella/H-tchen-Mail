package com.heikinashi.monitoring.domain;

/**
 * Port for rendering the inline HA chart attached to alert emails (CLAUDE.md §9).
 *
 * <p>Implementations may raise
 * {@link com.heikinashi.monitoring.domain.error.ChartRenderException} on
 * render failure.
 */
public interface ChartRenderer {

    /**
     * Render the alert chart over {@code bars} — the window the <b>caller</b> resolved via
     * {@link HaLookbackWindow#forEvent}. The renderer deliberately does NOT fetch its own
     * window: the AI analyst must describe the very bars drawn here, and a second read could
     * return a different series or fail independently (Block 18 invariant, CLAUDE.md).
     */
    ChartImage renderChart(PatternEvent event, java.util.List<HABar> bars);
}
