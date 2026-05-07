package com.heikinashi.monitoring.infrastructure;

import com.heikinashi.monitoring.application.InstrumentRegistry;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.UuidGenerator;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Factory
public class AppFactory {

    @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Singleton
    public InstrumentRegistry instrumentRegistry(
            InstrumentRepository repository,
            Clock clock,
            UuidGenerator uuids,
            @Value("${monitoring.exchanges.supported}") String supportedCsv) {
        Set<String> supported = Arrays.stream(supportedCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new InstrumentRegistry(repository, clock, uuids, supported);
    }
}
