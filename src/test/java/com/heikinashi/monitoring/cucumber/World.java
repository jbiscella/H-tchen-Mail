package com.heikinashi.monitoring.cucumber;

import com.heikinashi.monitoring.application.InMemoryInstrumentRepository;
import com.heikinashi.monitoring.application.InstrumentRegistry;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.UuidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared scenario state, created fresh by Picocontainer for each scenario.
 * Step definition classes that need to share state declare {@code World} as
 * a constructor parameter and PicoContainer wires the same instance to all
 * of them within a scenario.
 */
public final class World {

    private final InMemoryInstrumentRepository repository = new InMemoryInstrumentRepository();
    private final SequencedUuidGenerator uuids = new SequencedUuidGenerator();
    private Instant now = Instant.parse("2026-05-07T22:00:00Z");
    private InstrumentRegistry registry;
    private final Map<String, String> instrumentIdByAlias = new HashMap<>();

    private Instrument lastInstrument;
    private Page<Instrument> lastPage;
    private Throwable lastException;

    public InMemoryInstrumentRepository repository() {
        return repository;
    }

    public InstrumentRegistry registry() {
        if (registry == null) {
            throw new IllegalStateException("registry not initialised; call configureExchanges first");
        }
        return registry;
    }

    public void configureExchanges(Set<String> supported) {
        registry = new InstrumentRegistry(repository, Clock.fixed(now, ZoneOffset.UTC), uuids, supported);
    }

    public Instant now() {
        return now;
    }

    public void setNow(Instant now) {
        this.now = now;
    }

    public void rememberInstrument(String alias, Instrument instrument) {
        instrumentIdByAlias.put(alias, instrument.id());
        lastInstrument = instrument;
    }

    public String idByAlias(String alias) {
        String id = instrumentIdByAlias.get(alias);
        if (id == null) {
            throw new IllegalStateException("No instrument remembered under alias '" + alias + "'");
        }
        return id;
    }

    public Instrument lastInstrument() {
        return lastInstrument;
    }

    public void setLastInstrument(Instrument lastInstrument) {
        this.lastInstrument = lastInstrument;
    }

    public Page<Instrument> lastPage() {
        return lastPage;
    }

    public void setLastPage(Page<Instrument> lastPage) {
        this.lastPage = lastPage;
    }

    public Throwable lastException() {
        return lastException;
    }

    public void setLastException(Throwable lastException) {
        this.lastException = lastException;
    }

    public void clearException() {
        this.lastException = null;
    }

    private static final class SequencedUuidGenerator implements UuidGenerator {
        private final AtomicLong counter = new AtomicLong(0);

        @Override
        public UUID newUuid() {
            return new UUID(0L, counter.incrementAndGet());
        }
    }
}
