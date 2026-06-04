package com.heikinashi.monitoring.infrastructure.strategy;

import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyRepository;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Default {@link StrategyRepository}: no instrument has an imported strategy yet,
 * so every instrument keeps the legacy fixed-pattern detection. Persistence of
 * imported strategies (a DynamoDB STRATEGY item + the CLI import) is the
 * remaining wiring step on top of the Block 15-16 engine.
 */
@Singleton
public class NoOpStrategyRepository implements StrategyRepository {

    @Override
    public Optional<Strategy> findByInstrumentId(String instrumentId) {
        return Optional.empty();
    }
}
