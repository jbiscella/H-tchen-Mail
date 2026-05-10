package com.heikinashi.monitoring.cucumber;

import com.heikinashi.monitoring.application.AlertDispatchService;
import com.heikinashi.monitoring.application.CapturingAlertAuditRepository;
import com.heikinashi.monitoring.application.CapturingEmailSender;
import com.heikinashi.monitoring.application.HeikinAshiService;
import com.heikinashi.monitoring.application.InMemoryHaRepository;
import com.heikinashi.monitoring.application.InMemoryInstrumentRepository;
import com.heikinashi.monitoring.application.InMemoryMarketDataProvider;
import com.heikinashi.monitoring.application.InMemoryOhlcRepository;
import com.heikinashi.monitoring.application.InMemoryPendingAlertRepository;
import com.heikinashi.monitoring.application.IngestionConfig;
import com.heikinashi.monitoring.application.IngestionService;
import com.heikinashi.monitoring.application.InstrumentConfigService;
import com.heikinashi.monitoring.application.InstrumentRegistry;
import com.heikinashi.monitoring.application.MonitoringRunService;
import com.heikinashi.monitoring.application.PatternDetectionService;
import com.heikinashi.monitoring.application.RetryPollerService;
import com.heikinashi.monitoring.application.ScriptedAiAnalyst;
import com.heikinashi.monitoring.application.ScriptedChartRenderer;
import com.heikinashi.monitoring.application.config.AlertsConfig;
import com.heikinashi.monitoring.application.config.RetryConfig;
import com.heikinashi.monitoring.application.config.RunConfig;
import com.heikinashi.monitoring.domain.DispatchSummary;
import com.heikinashi.monitoring.domain.IngestionSummary;
import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.MainSummary;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PollResult;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.UuidGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private final InMemoryPendingAlertRepository pendingAlerts = new InMemoryPendingAlertRepository();
    private final ScriptedChartRenderer chartRenderer = new ScriptedChartRenderer();
    private final ScriptedAiAnalyst aiAnalyst = new ScriptedAiAnalyst();
    private final CapturingEmailSender emailSender = new CapturingEmailSender();
    private final CapturingAlertAuditRepository auditRepo = new CapturingAlertAuditRepository();
    private final List<PatternEvent> stagedEvents = new ArrayList<>();
    private final SequencedUuidGenerator uuids = new SequencedUuidGenerator();
    private Instant now = Instant.parse("2026-05-07T22:00:00Z");
    private InstrumentRegistry registry;
    private InstrumentConfigService configService;
    private IngestionService ingestionService;
    private HeikinAshiService heikinAshiService;
    private PatternDetectionService patternDetectionService;
    private AlertDispatchService alertDispatchService;
    private RetryPollerService retryPollerService;
    private MonitoringRunService monitoringRunService;
    private boolean auditEnabled;
    private Duration mainSoftTimeout = Duration.ofMinutes(13);
    private final Map<String, String> instrumentIdByAlias = new HashMap<>();

    private Instrument lastInstrument;
    private Page<Instrument> lastPage;
    private InstrumentConfig lastConfig;
    private IngestionSummary lastIngestionSummary;
    private DispatchSummary lastDispatchSummary;
    private PollResult lastPollResult;
    private MainSummary lastMainSummary;
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
        patternDetectionService = new PatternDetectionService(repository, ohlcRepository, haRepository, clock);
        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setMaxAttempts(3);
        retryConfig.setDelaySeconds((int) Duration.ofHours(1).toSeconds());
        retryConfig.setBatchLimit(100);
        AlertsConfig alertsConfig = new AlertsConfig();
        alertsConfig.setAuditEnabled(auditEnabled);
        RunConfig runConfig = new RunConfig();
        runConfig.setSoftTimeoutSeconds((int) mainSoftTimeout.toSeconds());
        alertDispatchService = new AlertDispatchService(
                repository,
                chartRenderer,
                aiAnalyst,
                emailSender,
                pendingAlerts,
                auditRepo,
                clock,
                retryConfig,
                alertsConfig);
        retryPollerService = new RetryPollerService(
                repository,
                chartRenderer,
                aiAnalyst,
                emailSender,
                pendingAlerts,
                auditRepo,
                clock,
                retryConfig,
                alertsConfig);
        monitoringRunService = new MonitoringRunService(
                repository,
                ingestionService,
                heikinAshiService,
                patternDetectionService,
                alertDispatchService,
                clock,
                runConfig);
    }

    public MonitoringRunService monitoringRunService() {
        if (monitoringRunService == null) {
            throw new IllegalStateException("monitoringRunService not initialised; call configureExchanges first");
        }
        return monitoringRunService;
    }

    public MainSummary lastMainSummary() {
        return lastMainSummary;
    }

    public void setLastMainSummary(MainSummary lastMainSummary) {
        this.lastMainSummary = lastMainSummary;
    }

    public void setMainSoftTimeout(Duration softTimeout) {
        this.mainSoftTimeout = softTimeout;
    }

    public InMemoryPendingAlertRepository pendingAlerts() {
        return pendingAlerts;
    }

    public ScriptedChartRenderer chartRenderer() {
        return chartRenderer;
    }

    public ScriptedAiAnalyst aiAnalyst() {
        return aiAnalyst;
    }

    public CapturingEmailSender emailSender() {
        return emailSender;
    }

    public CapturingAlertAuditRepository auditRepo() {
        return auditRepo;
    }

    public AlertDispatchService alertDispatchService() {
        if (alertDispatchService == null) {
            throw new IllegalStateException("alertDispatchService not initialised; call configureExchanges first");
        }
        return alertDispatchService;
    }

    public RetryPollerService retryPollerService() {
        if (retryPollerService == null) {
            throw new IllegalStateException("retryPollerService not initialised; call configureExchanges first");
        }
        return retryPollerService;
    }

    public List<PatternEvent> stagedEvents() {
        return stagedEvents;
    }

    public DispatchSummary lastDispatchSummary() {
        return lastDispatchSummary;
    }

    public void setLastDispatchSummary(DispatchSummary lastDispatchSummary) {
        this.lastDispatchSummary = lastDispatchSummary;
    }

    public PollResult lastPollResult() {
        return lastPollResult;
    }

    public void setLastPollResult(PollResult lastPollResult) {
        this.lastPollResult = lastPollResult;
    }

    public boolean auditEnabled() {
        return auditEnabled;
    }

    public void enableAudit() {
        this.auditEnabled = true;
    }

    public PatternDetectionService patternDetectionService() {
        if (patternDetectionService == null) {
            throw new IllegalStateException("patternDetectionService not initialised; call configureExchanges first");
        }
        return patternDetectionService;
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
