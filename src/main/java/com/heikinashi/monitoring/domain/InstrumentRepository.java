package com.heikinashi.monitoring.domain;

import java.util.Optional;

/**
 * Port for instrument persistence. Implementations live in {@code infrastructure}.
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>{@link #register(Instrument, InstrumentConfig)} is atomic across the
 *       META, CONFIG and UNIQUE_LOCK items, raising
 *       {@link com.heikinashi.monitoring.domain.error.DuplicateInstrumentException}
 *       on conflict.</li>
 *   <li>{@link #archive(String, java.time.Instant)} preserves the UNIQUE_LOCK
 *       and historical OHLC/HA data.</li>
 *   <li>{@link #hardDelete(String)} is idempotent: deleting a non-existent
 *       instrument is a no-op.</li>
 *   <li>{@link #updateConfig(String, InstrumentConfig)} is last-write-wins
 *       (no optimistic locking yet) and raises
 *       {@link com.heikinashi.monitoring.domain.error.InstrumentNotFoundException}
 *       if the META is absent.</li>
 * </ul>
 */
public interface InstrumentRepository {

    /** Atomically writes META + CONFIG + UNIQUE_LOCK. */
    void register(Instrument instrument, InstrumentConfig defaultConfig);

    Optional<Instrument> findById(String id);

    Page<Instrument> listByStatus(InstrumentStatus status, int pageSize, Optional<String> cursor);

    void updateMetadata(Instrument updated);

    void updateStatus(String id, InstrumentStatus newStatus, java.time.Instant updatedAt);

    /** Idempotent multi-step delete: bars then META+CONFIG+LOCK. No error if absent. */
    void hardDelete(String id);

    Optional<InstrumentConfig> findConfigById(String id);

    void updateConfig(String id, InstrumentConfig updated);
}
