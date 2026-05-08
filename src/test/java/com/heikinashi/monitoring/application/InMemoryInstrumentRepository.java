package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.error.DuplicateInstrumentException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Test fake. Models the same atomicity guarantees the DynamoDB adapter must
 * provide: a unique (ticker, exchange) lock and atomic META + CONFIG + LOCK
 * write on register. Idempotent hard delete. Last-write-wins config update.
 */
public final class InMemoryInstrumentRepository implements InstrumentRepository {

    private final Map<String, Instrument> byId = new LinkedHashMap<>();
    private final Map<String, String> locksByTickerKey = new HashMap<>();
    private final Map<String, InstrumentConfig> configById = new HashMap<>();

    @Override
    public void register(Instrument instrument, InstrumentConfig defaultConfig) {
        String lockKey = lockKey(instrument.ticker(), instrument.exchange());
        if (locksByTickerKey.containsKey(lockKey)) {
            throw new DuplicateInstrumentException(instrument.ticker(), instrument.exchange());
        }
        if (byId.containsKey(instrument.id())) {
            throw new DuplicateInstrumentException(instrument.ticker(), instrument.exchange());
        }
        locksByTickerKey.put(lockKey, instrument.id());
        byId.put(instrument.id(), instrument);
        configById.put(instrument.id(), defaultConfig);
    }

    @Override
    public Optional<Instrument> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Page<Instrument> listByStatus(InstrumentStatus status, int pageSize, Optional<String> cursor) {
        List<Instrument> filtered = new ArrayList<>();
        for (Instrument i : byId.values()) {
            if (i.status() == status) {
                filtered.add(i);
            }
        }
        filtered.sort(Comparator.comparing(Instrument::id));

        int startIdx = 0;
        if (cursor.isPresent()) {
            String afterId = cursor.get();
            for (int i = 0; i < filtered.size(); i++) {
                if (filtered.get(i).id().equals(afterId)) {
                    startIdx = i + 1;
                    break;
                }
            }
        }
        int endIdx = Math.min(startIdx + pageSize, filtered.size());
        List<Instrument> page = filtered.subList(startIdx, endIdx);
        Optional<String> next =
                endIdx < filtered.size() ? Optional.of(filtered.get(endIdx - 1).id()) : Optional.empty();
        return new Page<>(List.copyOf(page), next);
    }

    @Override
    public void updateMetadata(Instrument updated) {
        Instrument existing = byId.get(updated.id());
        if (existing == null) {
            throw new InstrumentNotFoundException(updated.id());
        }
        if (!Objects.equals(existing.ticker(), updated.ticker())
                || !Objects.equals(existing.exchange(), updated.exchange())
                || !Objects.equals(existing.createdAt(), updated.createdAt())
                || existing.status() != updated.status()) {
            throw new IllegalStateException("Attempted to mutate immutable field via updateMetadata");
        }
        byId.put(updated.id(), updated);
    }

    @Override
    public void updateStatus(String id, InstrumentStatus newStatus, Instant updatedAt) {
        Instrument existing = byId.get(id);
        if (existing == null) {
            throw new InstrumentNotFoundException(id);
        }
        byId.put(id, existing.withStatus(newStatus, updatedAt));
    }

    @Override
    public void hardDelete(String id) {
        Instrument existing = byId.remove(id);
        configById.remove(id);
        if (existing != null) {
            locksByTickerKey.remove(lockKey(existing.ticker(), existing.exchange()));
        }
    }

    @Override
    public Optional<InstrumentConfig> findConfigById(String id) {
        return Optional.ofNullable(configById.get(id));
    }

    @Override
    public void updateConfig(String id, InstrumentConfig updated) {
        if (!byId.containsKey(id)) {
            throw new InstrumentNotFoundException(id);
        }
        configById.put(id, updated);
    }

    public boolean hasLock(String ticker, String exchange) {
        return locksByTickerKey.containsKey(lockKey(ticker, exchange));
    }

    public boolean hasConfig(String instrumentId) {
        return configById.containsKey(instrumentId);
    }

    public int size() {
        return byId.size();
    }

    private static String lockKey(String ticker, String exchange) {
        return "TICKER#" + exchange + "#" + ticker;
    }
}
