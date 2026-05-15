package com.heikinashi.monitoring.domain;

import java.util.Optional;
import java.util.Set;

/**
 * Input for {@code monitoring-main} (CLAUDE.md §10).
 *
 * <p>{@link #instrumentIds()} empty means "all active"; populated means a
 * manual one-shot run for the listed instruments only.
 *
 * <p>{@link #forceEmail()} (default {@code false}) is a manual-testing
 * escape hatch: when true, any (instrument, timeframe) that the detector
 * would normally leave silent gets a synthetic "forced" pattern event
 * built from the latest persisted HA / OHLC bar, so chart + AI + email
 * all run end-to-end without waiting for a real pattern to fire.
 */
public record MainInput(Optional<Set<String>> instrumentIds, boolean forceEmail) {

    public MainInput {
        instrumentIds = instrumentIds.map(Set::copyOf);
    }

    public static MainInput allActive() {
        return new MainInput(Optional.empty(), false);
    }

    public static MainInput forInstruments(Set<String> ids) {
        return new MainInput(Optional.of(ids), false);
    }

    public MainInput withForceEmail(boolean value) {
        return new MainInput(instrumentIds, value);
    }
}
