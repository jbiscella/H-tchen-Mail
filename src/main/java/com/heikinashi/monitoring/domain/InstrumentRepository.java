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

    /**
     * Resolve an instrument from the identity a caller has as strings. Needed by Block 19: the
     * AI tool call carries only {@code ticker} / {@code exchange} (the model supplies them), so
     * the web-search provider cannot be handed the instrument and must look it up to reach the
     * name and the {@code news_query} override.
     *
     * <p>Backed by the ticker uniqueness lock, which already stores {@code instrument_id}, so
     * this is two point reads rather than a scan.
     */
    Optional<Instrument> findByTickerAndExchange(String ticker, String exchange);

    Page<Instrument> listByStatus(InstrumentStatus status, int pageSize, Optional<String> cursor);

    void updateMetadata(Instrument updated);

    void updateStatus(String id, InstrumentStatus newStatus, java.time.Instant updatedAt);

    /** Idempotent multi-step delete: bars then META+CONFIG+LOCK. No error if absent. */
    void hardDelete(String id);

    Optional<InstrumentConfig> findConfigById(String id);

    void updateConfig(String id, InstrumentConfig updated);
}
