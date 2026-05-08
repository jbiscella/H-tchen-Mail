package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternsConfig;
import com.heikinashi.monitoring.domain.StoragePolicy;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.EmptyRecipientsException;
import com.heikinashi.monitoring.domain.error.EmptyTimeframesException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import com.heikinashi.monitoring.domain.error.InvalidPatternConfigException;
import com.heikinashi.monitoring.domain.error.InvalidRecipientException;
import com.heikinashi.monitoring.domain.error.InvalidWindowSizeException;
import com.heikinashi.monitoring.domain.error.MissingWindowSizeException;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Block 2 — Per-Instrument Configuration use cases (CLAUDE.md §5).
 *
 * <p>The service reads the current config, applies a typed mutation, validates,
 * and persists the whole config back. Concurrent updates are last-write-wins
 * (optimistic locking deferred per the spec note).
 */
@Singleton
public class InstrumentConfigService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final InstrumentRepository repository;
    private final Clock clock;

    public InstrumentConfigService(InstrumentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public InstrumentConfig get(String id) {
        if (repository.findById(id).isEmpty()) {
            throw new InstrumentNotFoundException(id);
        }
        return repository.findConfigById(id).orElseThrow(() -> new InstrumentNotFoundException(id));
    }

    public InstrumentConfig updateStoragePolicy(String id, StoragePolicy policy, Optional<Integer> windowSize) {
        InstrumentConfig current = get(id);
        Optional<Integer> resolvedWindow =
                switch (policy) {
                    case FULL_HISTORY, SNAPSHOT_ONLY -> Optional.<Integer>empty();
                    case ROLLING_WINDOW -> {
                        if (windowSize.isEmpty()) {
                            throw new MissingWindowSizeException();
                        }
                        int w = windowSize.get();
                        if (w < 1) {
                            throw new InvalidWindowSizeException(w);
                        }
                        yield Optional.of(w);
                    }
                };
        InstrumentConfig updated = current.withStoragePolicy(policy, resolvedWindow, clock.instant());
        repository.updateConfig(id, updated);
        return updated;
    }

    public InstrumentConfig updateTimeframes(String id, Set<String> timeframes) {
        if (timeframes.isEmpty()) {
            throw new EmptyTimeframesException();
        }
        Set<Timeframe> normalized = new LinkedHashSet<>();
        for (String wire : timeframes) {
            normalized.add(Timeframe.fromWire(wire));
        }
        InstrumentConfig current = get(id);
        InstrumentConfig updated = current.withTrackedTimeframes(normalized, clock.instant());
        repository.updateConfig(id, updated);
        return updated;
    }

    public InstrumentConfig updatePattern(String id, String patternName, Map<String, Object> params) {
        PatternKind kind = PatternKind.fromWire(patternName);
        InstrumentConfig current = get(id);
        PatternsConfig patterns = current.patterns();
        PatternsConfig newPatterns =
                switch (kind) {
                    case COLOR_CHANGE -> patterns.withColorChange(buildColorChange(params, patterns.colorChange()));
                    case STRONG_CANDLE -> patterns.withStrongCandle(buildStrongCandle(params, patterns.strongCandle()));
                    case DOJI -> patterns.withDoji(buildDoji(params, patterns.doji()));
                };
        InstrumentConfig updated = current.withPatterns(newPatterns, clock.instant());
        repository.updateConfig(id, updated);
        return updated;
    }

    public InstrumentConfig updateRecipients(String id, Set<String> recipients) {
        if (recipients.isEmpty()) {
            throw new EmptyRecipientsException();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String r : recipients) {
            String trimmed = r == null ? null : r.trim().toLowerCase(Locale.ROOT);
            if (trimmed == null || !EMAIL.matcher(trimmed).matches()) {
                throw new InvalidRecipientException(r);
            }
            normalized.add(trimmed);
        }
        InstrumentConfig current = get(id);
        InstrumentConfig updated = current.withRecipients(normalized, clock.instant());
        repository.updateConfig(id, updated);
        return updated;
    }

    private PatternsConfig.ColorChange buildColorChange(Map<String, Object> params, PatternsConfig.ColorChange prev) {
        boolean enabled = boolParam(params, "enabled", prev.enabled());
        int minStreak = intParam(params, "min_streak_length", prev.minStreakLength());
        if (minStreak < 1) {
            throw new InvalidPatternConfigException("color_change", "min_streak_length", minStreak);
        }
        return new PatternsConfig.ColorChange(enabled, minStreak);
    }

    private PatternsConfig.StrongCandle buildStrongCandle(
            Map<String, Object> params, PatternsConfig.StrongCandle prev) {
        boolean enabled = boolParam(params, "enabled", prev.enabled());
        BigDecimal wickTolerance = decimalParam(params, "wick_tolerance", prev.wickTolerance());
        BigDecimal minBodyRatio = decimalParam(params, "min_body_ratio", prev.minBodyRatio());
        if (wickTolerance.signum() < 0) {
            throw new InvalidPatternConfigException("strong_candle", "wick_tolerance", wickTolerance);
        }
        if (minBodyRatio.compareTo(ZERO) <= 0 || minBodyRatio.compareTo(ONE) > 0) {
            throw new InvalidPatternConfigException("strong_candle", "min_body_ratio", minBodyRatio);
        }
        return new PatternsConfig.StrongCandle(enabled, wickTolerance, minBodyRatio);
    }

    private PatternsConfig.Doji buildDoji(Map<String, Object> params, PatternsConfig.Doji prev) {
        boolean enabled = boolParam(params, "enabled", prev.enabled());
        BigDecimal maxBodyRatio = decimalParam(params, "max_body_ratio", prev.maxBodyRatio());
        if (maxBodyRatio.compareTo(ZERO) <= 0 || maxBodyRatio.compareTo(ONE) > 0) {
            throw new InvalidPatternConfigException("doji", "max_body_ratio", maxBodyRatio);
        }
        return new PatternsConfig.Doji(enabled, maxBodyRatio);
    }

    private static boolean boolParam(Map<String, Object> params, String key, boolean fallback) {
        Object v = params.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object v = params.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(v.toString());
    }

    private static BigDecimal decimalParam(Map<String, Object> params, String key, BigDecimal fallback) {
        Object v = params.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(v.toString());
    }

    @SuppressWarnings("unused")
    private Instant now() {
        return clock.instant();
    }
}
