package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentRepository;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.UuidGenerator;
import com.heikinashi.monitoring.domain.error.ImmutableFieldException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import com.heikinashi.monitoring.domain.error.InvalidTickerException;
import com.heikinashi.monitoring.domain.error.UnsupportedExchangeException;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Block 1 — Instrument Registry use cases. Pure orchestration over the
 * {@link InstrumentRepository} port; no DynamoDB / SDK types leak in.
 */
@Singleton
public class InstrumentRegistry {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final InstrumentRepository repository;
    private final Clock clock;
    private final UuidGenerator uuids;
    private final Set<String> supportedExchanges;

    public InstrumentRegistry(
            InstrumentRepository repository, Clock clock, UuidGenerator uuids, Set<String> supportedExchanges) {
        this.repository = repository;
        this.clock = clock;
        this.uuids = uuids;
        this.supportedExchanges = Set.copyOf(supportedExchanges);
    }

    public Instrument register(String rawTicker, String rawExchange, Optional<String> name, Optional<String> currency) {
        String ticker = normalizeTicker(rawTicker);
        String exchange = normalizeExchange(rawExchange);

        Instant now = clock.instant();
        Instrument instrument = new Instrument(
                uuids.newUuid().toString(), ticker, exchange, name, currency, InstrumentStatus.ACTIVE, now, now);
        repository.register(instrument);
        return instrument;
    }

    public Instrument get(String id) {
        return repository.findById(id).orElseThrow(() -> new InstrumentNotFoundException(id));
    }

    public Page<Instrument> list(InstrumentStatus status, int pageSize, Optional<String> cursor) {
        int effective = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return repository.listByStatus(status, effective, cursor);
    }

    public Instrument updateMetadata(String id, Optional<String> name, Optional<String> currency) {
        Instrument current = get(id);
        Instrument updated = current.withMetadata(name, currency, clock.instant());
        repository.updateMetadata(updated);
        return updated;
    }

    /** Helper for callers that pass a field map; rejects any attempt to mutate immutable fields. */
    public void rejectImmutableUpdates(Set<String> attemptedFields) {
        for (String field : attemptedFields) {
            switch (field) {
                case "id", "ticker", "exchange", "createdAt", "created_at" -> throw new ImmutableFieldException(field);
                default -> {
                    /* mutable */
                }
            }
        }
    }

    public Instrument archive(String id) {
        Instrument current = get(id);
        if (current.status() == InstrumentStatus.ARCHIVED) {
            return current;
        }
        Instant now = clock.instant();
        repository.updateStatus(id, InstrumentStatus.ARCHIVED, now);
        return current.withStatus(InstrumentStatus.ARCHIVED, now);
    }

    public Instrument restore(String id) {
        Instrument current = get(id);
        if (current.status() == InstrumentStatus.ACTIVE) {
            return current;
        }
        Instant now = clock.instant();
        repository.updateStatus(id, InstrumentStatus.ACTIVE, now);
        return current.withStatus(InstrumentStatus.ACTIVE, now);
    }

    public void delete(String id) {
        repository.hardDelete(id);
    }

    private String normalizeTicker(String raw) {
        if (raw == null) {
            throw new InvalidTickerException("ticker", "null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || containsWhitespace(trimmed)) {
            throw new InvalidTickerException("ticker", raw);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private String normalizeExchange(String raw) {
        if (raw == null) {
            throw new UnsupportedExchangeException("null", supportedExchanges);
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!supportedExchanges.contains(upper)) {
            throw new UnsupportedExchangeException(upper, supportedExchanges);
        }
        return upper;
    }

    private static boolean containsWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
