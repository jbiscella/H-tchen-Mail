package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.OhlcRepository;
import com.heikinashi.monitoring.domain.Timeframe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Test fake. Models the same idempotent put + SNAPSHOT_ONLY truncate-and-put contract. */
public final class InMemoryOhlcRepository implements OhlcRepository {

    private final Map<String, TreeMap<Instant, OHLCBar>> byKey = new HashMap<>();
    private final Map<String, Long> ttlByKey = new HashMap<>();

    @Override
    public Optional<OHLCBar> findLatest(String instrumentId, Timeframe tf) {
        TreeMap<Instant, OHLCBar> bars = byKey.get(key(instrumentId, tf));
        if (bars == null || bars.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(bars.lastEntry().getValue());
    }

    @Override
    public boolean putBar(OHLCBar bar, Optional<Long> ttl) {
        TreeMap<Instant, OHLCBar> bars =
                byKey.computeIfAbsent(key(bar.instrumentId(), bar.timeframe()), k -> new TreeMap<>());
        if (bars.containsKey(bar.barTime())) {
            return false;
        }
        bars.put(bar.barTime(), bar);
        ttl.ifPresent(t -> ttlByKey.put(itemKey(bar), t));
        return true;
    }

    @Override
    public void snapshotReplace(String instrumentId, Timeframe tf, OHLCBar newBar, Optional<Long> ttl) {
        TreeMap<Instant, OHLCBar> bars = byKey.computeIfAbsent(key(instrumentId, tf), k -> new TreeMap<>());
        for (OHLCBar existing : new ArrayList<>(bars.values())) {
            ttlByKey.remove(itemKey(existing));
        }
        bars.clear();
        bars.put(newBar.barTime(), newBar);
        ttl.ifPresent(t -> ttlByKey.put(itemKey(newBar), t));
    }

    @Override
    public List<OHLCBar> listAll(String instrumentId, Timeframe tf) {
        TreeMap<Instant, OHLCBar> bars = byKey.get(key(instrumentId, tf));
        if (bars == null) {
            return List.of();
        }
        List<OHLCBar> out = new ArrayList<>(bars.values());
        out.sort(Comparator.comparing(OHLCBar::barTime));
        return out;
    }

    public Optional<Long> ttlFor(OHLCBar bar) {
        return Optional.ofNullable(ttlByKey.get(itemKey(bar)));
    }

    public int totalBars() {
        int n = 0;
        for (TreeMap<Instant, OHLCBar> bars : byKey.values()) {
            n += bars.size();
        }
        return n;
    }

    private static String key(String instrumentId, Timeframe tf) {
        return instrumentId + "#" + tf.wire();
    }

    private static String itemKey(OHLCBar bar) {
        return bar.instrumentId() + "#" + bar.timeframe().wire() + "#" + bar.barTime();
    }
}
