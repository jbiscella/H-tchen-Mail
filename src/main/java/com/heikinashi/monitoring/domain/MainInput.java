package com.heikinashi.monitoring.domain;

import java.util.Optional;
import java.util.Set;

/**
 * Input for {@code monitoring-main} (CLAUDE.md §10).
 *
 * <p>{@link #instrumentIds()} empty means "all active"; populated means a
 * manual one-shot run for the listed instruments only.
 */
public record MainInput(Optional<Set<String>> instrumentIds) {

    public MainInput {
        instrumentIds = instrumentIds.map(Set::copyOf);
    }

    public static MainInput allActive() {
        return new MainInput(Optional.empty());
    }

    public static MainInput forInstruments(Set<String> ids) {
        return new MainInput(Optional.of(ids));
    }
}
