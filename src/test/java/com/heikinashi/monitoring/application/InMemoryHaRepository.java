package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.Timeframe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Test fake. Mirrors the DynamoDB adapter's overwrite semantics + SNAPSHOT_ONLY truncate-and-put. */
public final class InMemoryHaRepository implements HaRepository {

    private final Map<String, TreeMap<Instant, HABar>> byKey = new HashMap<>();
    private final Map<String, Long> ttlByKey = new HashMap<>();

    @Override
    public Optional<HABar> findLatestBefore(String instrumentId, Timeframe tf, Instant before) {
        TreeMap<Instant, HABar> bars = byKey.get(key(instrumentId, tf));
        if (bars == null || bars.isEmpty()) {
            return Optional.empty();
        }
        Map.Entry<Instant, HABar> entry = bars.lowerEntry(before);
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    @Override
    public List<HABar> findLastNBefore(String instrumentId, Timeframe tf, Instant before, int n) {
        TreeMap<Instant, HABar> bars = byKey.get(key(instrumentId, tf));
        if (bars == null || bars.isEmpty() || n <= 0) {
            return List.of();
        }
        List<HABar> headView = new ArrayList<>(bars.headMap(before, false).values());
        int from = Math.max(0, headView.size() - n);
        return new ArrayList<>(headView.subList(from, headView.size()));
    }

    @Override
    public void putBar(HABar bar, Optional<Long> ttl) {
        TreeMap<Instant, HABar> bars =
                byKey.computeIfAbsent(key(bar.instrumentId(), bar.timeframe()), k -> new TreeMap<>());
        bars.put(bar.barTime(), bar);
        ttl.ifPresentOrElse(t -> ttlByKey.put(itemKey(bar), t), () -> ttlByKey.remove(itemKey(bar)));
    }

    @Override
    public void snapshotReplace(String instrumentId, Timeframe tf, HABar newBar, Optional<Long> ttl) {
        TreeMap<Instant, HABar> bars = byKey.computeIfAbsent(key(instrumentId, tf), k -> new TreeMap<>());
        for (HABar existing : new ArrayList<>(bars.values())) {
            ttlByKey.remove(itemKey(existing));
        }
        bars.clear();
        bars.put(newBar.barTime(), newBar);
        ttl.ifPresent(t -> ttlByKey.put(itemKey(newBar), t));
    }

    @Override
    public List<HABar> listAll(String instrumentId, Timeframe tf) {
        TreeMap<Instant, HABar> bars = byKey.get(key(instrumentId, tf));
        return bars == null ? List.of() : new ArrayList<>(bars.values());
    }

    @Override
    public void deleteAll(String instrumentId, Timeframe tf) {
        TreeMap<Instant, HABar> bars = byKey.remove(key(instrumentId, tf));
        if (bars != null) {
            for (HABar bar : bars.values()) {
                ttlByKey.remove(itemKey(bar));
            }
        }
    }

    public Optional<Long> ttlFor(HABar bar) {
        return Optional.ofNullable(ttlByKey.get(itemKey(bar)));
    }

    private static String key(String instrumentId, Timeframe tf) {
        return instrumentId + "#" + tf.wire();
    }

    private static String itemKey(HABar bar) {
        return bar.instrumentId() + "#" + bar.timeframe().wire() + "#" + bar.barTime();
    }
}
