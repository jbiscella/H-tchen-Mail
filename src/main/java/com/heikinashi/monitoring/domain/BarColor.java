package com.heikinashi.monitoring.domain;

/** Heikin-Ashi candle colour (CLAUDE.md §8). */
public enum BarColor {
    GREEN,
    RED,
    NEUTRAL;

    public static BarColor of(HABar bar) {
        int cmp = bar.haClose().compareTo(bar.haOpen());
        if (cmp > 0) return GREEN;
        if (cmp < 0) return RED;
        return NEUTRAL;
    }
}
