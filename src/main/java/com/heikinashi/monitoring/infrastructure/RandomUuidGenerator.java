package com.heikinashi.monitoring.infrastructure;

import com.heikinashi.monitoring.domain.UuidGenerator;
import jakarta.inject.Singleton;
import java.util.UUID;

@Singleton
public final class RandomUuidGenerator implements UuidGenerator {
    @Override
    public UUID newUuid() {
        return UUID.randomUUID();
    }
}
