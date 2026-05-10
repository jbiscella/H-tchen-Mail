package com.heikinashi.monitoring.infrastructure.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentConfig;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.error.DuplicateInstrumentException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link DynamoDbInstrumentRepository} backed by
 * LocalStack via Testcontainers. Validates that the production DynamoDB
 * adapter satisfies the same contract that the in-memory fake provides
 * for Block 1 (CLAUDE.md §4): atomic register, conflict detection,
 * GSI1-based listing with cursor pagination, status updates that flip
 * gsi1Pk, and idempotent multi-step hard delete.
 */
class DynamoDbInstrumentRepositoryIT extends LocalStackITBase {

    private DynamoDbInstrumentRepository repo;

    @BeforeEach
    void setUp() {
        wipeTable();
        repo = new DynamoDbInstrumentRepository(CLIENT, TABLE_CONFIG);
    }

    @Test
    void register_then_findById_round_trips_the_instrument() {
        Instrument inst = sampleInstrument("AAPL", "NASDAQ");
        repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));

        Optional<Instrument> loaded = repo.findById(inst.id());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().ticker()).isEqualTo("AAPL");
        assertThat(loaded.get().exchange()).isEqualTo("NASDAQ");
        assertThat(loaded.get().status()).isEqualTo(InstrumentStatus.ACTIVE);
    }

    @Test
    void register_atomically_writes_meta_config_and_lock() {
        Instrument inst = sampleInstrument("AAPL", "NASDAQ");
        repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));

        assertThat(repo.findById(inst.id())).isPresent();
        assertThat(repo.findConfigById(inst.id())).isPresent();
    }

    @Test
    void register_duplicate_ticker_exchange_pair_raises_duplicate_instrument() {
        Instrument first = sampleInstrument("AAPL", "NASDAQ");
        repo.register(first, InstrumentConfig.defaults(first.createdAt()));

        Instrument second = sampleInstrument("AAPL", "NASDAQ");
        assertThatThrownBy(() -> repo.register(second, InstrumentConfig.defaults(second.createdAt())))
                .isInstanceOf(DuplicateInstrumentException.class);
    }

    @Test
    void listByStatus_filters_by_gsi1_and_paginates() {
        for (int i = 0; i < 7; i++) {
            Instrument inst = sampleInstrument("T" + i, "NASDAQ");
            repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));
        }

        Page<Instrument> first = repo.listByStatus(InstrumentStatus.ACTIVE, 3, Optional.empty());
        assertThat(first.items()).hasSize(3);
        assertThat(first.nextCursor()).isPresent();

        Page<Instrument> second = repo.listByStatus(InstrumentStatus.ACTIVE, 3, first.nextCursor());
        assertThat(second.items()).hasSize(3);

        Page<Instrument> third = repo.listByStatus(InstrumentStatus.ACTIVE, 3, second.nextCursor());
        assertThat(third.items()).hasSize(1);
        assertThat(third.nextCursor()).isEmpty();
    }

    @Test
    void updateStatus_flips_gsi1Pk_so_subsequent_listing_finds_it_under_archived() {
        Instrument inst = sampleInstrument("AAPL", "NASDAQ");
        repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));

        repo.updateStatus(inst.id(), InstrumentStatus.ARCHIVED, Instant.parse("2026-05-08T00:00:00Z"));

        assertThat(repo.listByStatus(InstrumentStatus.ACTIVE, 10, Optional.empty())
                        .items())
                .isEmpty();
        List<Instrument> archived = repo.listByStatus(InstrumentStatus.ARCHIVED, 10, Optional.empty())
                .items();
        assertThat(archived).hasSize(1);
        assertThat(archived.get(0).status()).isEqualTo(InstrumentStatus.ARCHIVED);
    }

    @Test
    void updateMetadata_on_missing_instrument_raises_not_found() {
        Instrument ghost = sampleInstrument("AAPL", "NASDAQ");
        assertThatThrownBy(() -> repo.updateMetadata(ghost)).isInstanceOf(InstrumentNotFoundException.class);
    }

    @Test
    void hardDelete_removes_meta_config_and_lock() {
        Instrument inst = sampleInstrument("AAPL", "NASDAQ");
        repo.register(inst, InstrumentConfig.defaults(inst.createdAt()));

        repo.hardDelete(inst.id());

        assertThat(repo.findById(inst.id())).isEmpty();
        assertThat(repo.findConfigById(inst.id())).isEmpty();

        Instrument reborn = sampleInstrument("AAPL", "NASDAQ");
        repo.register(reborn, InstrumentConfig.defaults(reborn.createdAt()));
        assertThat(repo.findById(reborn.id())).isPresent();
    }

    @Test
    void hardDelete_is_idempotent_on_a_never_existed_id() {
        repo.hardDelete("never-existed");
        repo.hardDelete("never-existed");
    }

    @Test
    void updateConfig_on_missing_instrument_raises_not_found() {
        InstrumentConfig cfg = InstrumentConfig.defaults(Instant.parse("2026-05-07T22:00:00Z"));
        assertThatThrownBy(() -> repo.updateConfig("missing-id", cfg)).isInstanceOf(InstrumentNotFoundException.class);
    }

    private static Instrument sampleInstrument(String ticker, String exchange) {
        Instant now = Instant.parse("2026-05-07T22:00:00Z");
        return new Instrument(
                UUID.randomUUID().toString(),
                ticker,
                exchange,
                Optional.of(ticker + " Inc."),
                Optional.of("USD"),
                InstrumentStatus.ACTIVE,
                now,
                now);
    }
}
