package com.heikinashi.monitoring.cucumber;

import com.heikinashi.monitoring.application.HeikinAshiService;
import com.heikinashi.monitoring.application.InMemoryHaRepository;
import com.heikinashi.monitoring.application.InMemoryInstrumentRepository;
import com.heikinashi.monitoring.application.InMemoryMarketDataProvider;
import com.heikinashi.monitoring.application.InMemoryOhlcRepository;
import com.heikinashi.monitoring.application.IngestionConfig;
import com.heikinashi.monitoring.application.IngestionService;
import com.heikinashi.monitoring.application.InstrumentConfigService;
import com.heikinashi.monitoring.application.InstrumentRegistry;
import com.heikinashi.monitoring.domain.IngestionSummary;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.Timeframe;
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
    private final InMemoryOhlcRepository ohlcRepository = new InMemoryOhlcRepository();
    private final InMemoryHaRepository haRepository = new InMemoryHaRepository();
    private final InMemoryMarketDataProvider marketData = new InMemoryMarketDataProvider();
    private final SequencedUuidGenerator uuids = new SequencedUuidGenerator();
    private Instant now = Instant.parse("2026-05-07T22:00:00Z");
    private InstrumentRegistry registry;
    private InstrumentConfigService configService;
    private IngestionService ingestionService;
    private HeikinAshiService heikinAshiService;
    private final Map<String, String> instrumentIdByAlias = new HashMap<>();

    private Instrument lastInstrument;
    private Page<Instrument> lastPage;
    private InstrumentConfig lastConfig;
    private IngestionSummary lastIngestionSummary;
    private Throwable lastException;

    public InMemoryInstrumentRepository repository() {
        return repository;
    }

    public InMemoryOhlcRepository ohlcRepository() {
        return ohlcRepository;
    }

    public InMemoryHaRepository haRepository() {
        return haRepository;
    }

    public InMemoryMarketDataProvider marketData() {
        return marketData;
    }

    public InstrumentRegistry registry() {
        if (registry == null) {
            throw new IllegalStateException("registry not initialised; call configureExchanges first");
        }
        return registry;
    }

    public void configureExchanges(Set<String> supported) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        registry = new InstrumentRegistry(repository, clock, uuids, supported);
        configService = new InstrumentConfigService(repository, clock);
        IngestionConfig ingCfg = new IngestionConfig(
                3,
                0.5,
                Map.of(Timeframe.D1, 250, Timeframe.W1, 260),
                Map.of(
                        "MIL", ".MI",
                        "XETRA", ".DE",
                        "LSE", ".L",
                        "TSX", ".TO",
                        "PAR", ".PA",
                        "AMS", ".AS"));
        ingestionService = new IngestionService(repository, ohlcRepository, marketData, clock, ingCfg);
        heikinAshiService = new HeikinAshiService(repository, ohlcRepository, haRepository, clock);
    }

    public HeikinAshiService heikinAshiService() {
        if (heikinAshiService == null) {
            throw new IllegalStateException("heikinAshiService not initialised; call configureExchanges first");
        }
        return heikinAshiService;
    }

    public InstrumentConfigService configService() {
        if (configService == null) {
            throw new IllegalStateException("configService not initialised; call configureExchanges first");
        }
        return configService;
    }

    public IngestionService ingestionService() {
        if (ingestionService == null) {
            throw new IllegalStateException("ingestionService not initialised; call configureExchanges first");
        }
        return ingestionService;
    }

    public InstrumentConfig lastConfig() {
        return lastConfig;
    }

    public void setLastConfig(InstrumentConfig lastConfig) {
        this.lastConfig = lastConfig;
    }

    public IngestionSummary lastIngestionSummary() {
        return lastIngestionSummary;
    }

    public void setLastIngestionSummary(IngestionSummary lastIngestionSummary) {
        this.lastIngestionSummary = lastIngestionSummary;
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
