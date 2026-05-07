package com.heikinashi.monitoring.domain;

import java.util.UUID;

@FunctionalInterface
public interface UuidGenerator {
    UUID newUuid();
}
