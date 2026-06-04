package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link StrategyRepository} for tests; default state has no strategies. */
public final class InMemoryStrategyRepository implements StrategyRepository {

    private final Map<String, Strategy> byInstrument = new HashMap<>();

    public void put(String instrumentId, Strategy strategy) {
        byInstrument.put(instrumentId, strategy);
    }

    @Override
    public Optional<Strategy> findByInstrumentId(String instrumentId) {
        return Optional.ofNullable(byInstrument.get(instrumentId));
    }
}
