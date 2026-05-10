package com.heikinashi.monitoring.domain;

/**
 * Port for rendering the inline HA chart attached to alert emails (CLAUDE.md §9).
 *
 * <p>Implementations may raise
 * {@link com.heikinashi.monitoring.domain.error.ChartRenderException} on
 * render failure.
 */
public interface ChartRenderer {
    ChartImage renderChart(PatternEvent event);
}
