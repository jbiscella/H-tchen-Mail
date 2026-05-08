package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.error.InvalidStoragePolicyException;
import java.util.Set;

public enum StoragePolicy {
    FULL_HISTORY,
    ROLLING_WINDOW,
    SNAPSHOT_ONLY;

    public String wire() {
        return name();
    }

    public static StoragePolicy fromWire(String wire) {
        for (StoragePolicy p : values()) {
            if (p.name().equals(wire)) {
                return p;
            }
        }
        throw new InvalidStoragePolicyException(
                wire, Set.of(FULL_HISTORY.name(), ROLLING_WINDOW.name(), SNAPSHOT_ONLY.name()));
    }
}
