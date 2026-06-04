package com.heikinashi.monitoring.domain.strategy;

import java.util.Optional;

/**
 * Lookup of the imported strategy (if any) monitored for an instrument
 * (Blocks 15-16). An instrument with a strategy has its three fixed patterns
 * superseded; one without keeps the legacy color_change / strong_candle / doji
 * detection.
 */
public interface StrategyRepository {

    Optional<Strategy> findByInstrumentId(String instrumentId);
}
