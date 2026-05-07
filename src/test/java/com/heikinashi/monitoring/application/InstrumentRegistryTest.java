package com.heikinashi.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.Page;
import com.heikinashi.monitoring.domain.UuidGenerator;
import com.heikinashi.monitoring.domain.error.DuplicateInstrumentException;
import com.heikinashi.monitoring.domain.error.ImmutableFieldException;
import com.heikinashi.monitoring.domain.error.InstrumentNotFoundException;
import com.heikinashi.monitoring.domain.error.InvalidTickerException;
import com.heikinashi.monitoring.domain.error.UnsupportedExchangeException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InstrumentRegistryTest {

    private static final Set<String> EXCHANGES = Set.of("NASDAQ", "NYSE", "MIL", "XETRA", "LSE", "TSX", "PAR", "AMS");
    private static final Instant FIXED = Instant.parse("2026-05-07T22:00:00Z");

    private InMemoryInstrumentRepository repo;
    private InstrumentRegistry registry;
    private SequencedUuidGenerator uuids;

    @BeforeEach
    void setUp() {
        repo = new InMemoryInstrumentRepository();
        uuids = new SequencedUuidGenerator();
        registry = new InstrumentRegistry(repo, Clock.fixed(FIXED, ZoneOffset.UTC), uuids, EXCHANGES);
    }

    @Test
    void registers_a_valid_equity_with_normalization_and_atomic_writes() {
        Instrument i = registry.register("aapl", "nasdaq", Optional.of("Apple Inc."), Optional.of("USD"));

        assertThat(i.ticker()).isEqualTo("AAPL");
        assertThat(i.exchange()).isEqualTo("NASDAQ");
        assertThat(i.status()).isEqualTo(InstrumentStatus.ACTIVE);
        assertThat(i.createdAt()).isEqualTo(FIXED);
        assertThat(i.updatedAt()).isEqualTo(FIXED);
        assertThat(repo.hasLock("AAPL", "NASDAQ")).isTrue();
        assertThat(repo.hasConfig(i.id())).isTrue();
    }

    @Test
    void rejects_duplicate_ticker_exchange_pair() {
        registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty()))
                .isInstanceOf(DuplicateInstrumentException.class)
                .extracting(t -> ((DuplicateInstrumentException) t).code())
                .isEqualTo("DUPLICATE_INSTRUMENT");
    }

    @Test
    void rejects_empty_ticker_before_any_persistence() {
        assertThatThrownBy(() -> registry.register("", "NASDAQ", Optional.empty(), Optional.empty()))
                .isInstanceOf(InvalidTickerException.class);
        assertThat(repo.size()).isZero();
    }

    @Test
    void rejects_whitespace_ticker() {
        assertThatThrownBy(() -> registry.register("AA PL", "NASDAQ", Optional.empty(), Optional.empty()))
                .isInstanceOf(InvalidTickerException.class);
        assertThat(repo.size()).isZero();
    }

    @Test
    void rejects_unsupported_exchange() {
        assertThatThrownBy(() -> registry.register("AAPL", "FOOBAR", Optional.empty(), Optional.empty()))
                .isInstanceOf(UnsupportedExchangeException.class);
    }

    @Test
    void gets_existing_instrument() {
        Instrument created = registry.register("MSFT", "NASDAQ", Optional.empty(), Optional.empty());
        assertThat(registry.get(created.id())).isEqualTo(created);
    }

    @Test
    void getting_unknown_instrument_raises_not_found() {
        assertThatThrownBy(() -> registry.get("missing-id"))
                .isInstanceOf(InstrumentNotFoundException.class)
                .extracting(t -> ((InstrumentNotFoundException) t).payload().get("id"))
                .isEqualTo("missing-id");
    }

    @Test
    void list_returns_only_instruments_with_matching_status() {
        registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());
        registry.register("MSFT", "NASDAQ", Optional.empty(), Optional.empty());
        Instrument tobeArchived = registry.register("GOOG", "NASDAQ", Optional.empty(), Optional.empty());
        registry.archive(tobeArchived.id());

        Page<Instrument> active = registry.list(InstrumentStatus.ACTIVE, 50, Optional.empty());
        Page<Instrument> archived = registry.list(InstrumentStatus.ARCHIVED, 50, Optional.empty());

        assertThat(active.items()).hasSize(2);
        assertThat(archived.items()).hasSize(1);
    }

    @Test
    void list_paginates_with_cursor() {
        for (int i = 0; i < 30; i++) {
            registry.register("T" + i, "NASDAQ", Optional.empty(), Optional.empty());
        }

        Page<Instrument> first = registry.list(InstrumentStatus.ACTIVE, 10, Optional.empty());
        assertThat(first.items()).hasSize(10);
        assertThat(first.nextCursor()).isPresent();

        Page<Instrument> second = registry.list(InstrumentStatus.ACTIVE, 10, first.nextCursor());
        assertThat(second.items()).hasSize(10);
        assertThat(second.nextCursor()).isPresent();

        Page<Instrument> third = registry.list(InstrumentStatus.ACTIVE, 10, second.nextCursor());
        assertThat(third.items()).hasSize(10);
        assertThat(third.nextCursor()).isEmpty();
    }

    @Test
    void update_metadata_changes_only_mutable_fields_and_bumps_updated_at() {
        Instrument created = registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());

        Instrument updated = registry.updateMetadata(created.id(), Optional.of("Apple Inc."), Optional.of("USD"));

        assertThat(updated.name()).contains("Apple Inc.");
        assertThat(updated.currency()).contains("USD");
        assertThat(updated.ticker()).isEqualTo(created.ticker());
        assertThat(updated.exchange()).isEqualTo(created.exchange());
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.id()).isEqualTo(created.id());
    }

    @Test
    void rejects_attempts_to_update_immutable_fields() {
        for (String field : new String[] {"id", "ticker", "exchange", "created_at", "createdAt"}) {
            assertThatThrownBy(() -> registry.rejectImmutableUpdates(Set.of(field)))
                    .as("expected ImmutableFieldException for %s", field)
                    .isInstanceOf(ImmutableFieldException.class);
        }
    }

    @Test
    void archive_flips_status_and_keeps_lock_and_history() {
        Instrument created = registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());

        Instrument archived = registry.archive(created.id());

        assertThat(archived.status()).isEqualTo(InstrumentStatus.ARCHIVED);
        assertThat(repo.hasLock("AAPL", "NASDAQ")).isTrue();
    }

    @Test
    void restore_flips_status_back_to_active() {
        Instrument created = registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());
        registry.archive(created.id());

        Instrument restored = registry.restore(created.id());

        assertThat(restored.status()).isEqualTo(InstrumentStatus.ACTIVE);
    }

    @Test
    void archive_unknown_instrument_raises_not_found() {
        assertThatThrownBy(() -> registry.archive("missing-id")).isInstanceOf(InstrumentNotFoundException.class);
    }

    @Test
    void hard_delete_removes_all_traces() {
        Instrument created = registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());

        registry.delete(created.id());

        assertThat(repo.hasLock("AAPL", "NASDAQ")).isFalse();
        assertThat(repo.hasConfig(created.id())).isFalse();
        assertThatThrownBy(() -> registry.get(created.id())).isInstanceOf(InstrumentNotFoundException.class);
    }

    @Test
    void hard_delete_is_idempotent() {
        registry.delete("never-existed");
        registry.delete("never-existed");
    }

    @Test
    void list_with_non_positive_page_size_falls_back_to_default() {
        registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());

        Page<Instrument> defaulted = registry.list(InstrumentStatus.ACTIVE, 0, Optional.empty());
        Page<Instrument> negative = registry.list(InstrumentStatus.ACTIVE, -5, Optional.empty());

        assertThat(defaulted.items()).hasSize(1);
        assertThat(negative.items()).hasSize(1);
    }

    @Test
    void list_caps_page_size_to_maximum() {
        for (int i = 0; i < 5; i++) {
            registry.register("T" + i, "NASDAQ", Optional.empty(), Optional.empty());
        }
        Page<Instrument> page = registry.list(InstrumentStatus.ACTIVE, 1_000_000, Optional.empty());
        assertThat(page.items()).hasSize(5);
    }

    @Test
    void archive_is_idempotent() {
        Instrument created = registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());
        Instrument firstArchive = registry.archive(created.id());
        Instrument secondArchive = registry.archive(created.id());

        assertThat(firstArchive.status()).isEqualTo(InstrumentStatus.ARCHIVED);
        assertThat(secondArchive.status()).isEqualTo(InstrumentStatus.ARCHIVED);
    }

    @Test
    void restore_is_idempotent_on_already_active_instrument() {
        Instrument created = registry.register("AAPL", "NASDAQ", Optional.empty(), Optional.empty());
        Instrument restored = registry.restore(created.id());
        assertThat(restored.status()).isEqualTo(InstrumentStatus.ACTIVE);
    }

    @Test
    void reject_immutable_updates_accepts_mutable_field_names() {
        registry.rejectImmutableUpdates(Set.of("name", "currency"));
        registry.rejectImmutableUpdates(Set.of());
    }

    @Test
    void register_rejects_null_ticker_and_null_exchange() {
        assertThatThrownBy(() -> registry.register(null, "NASDAQ", Optional.empty(), Optional.empty()))
                .isInstanceOf(InvalidTickerException.class);
        assertThatThrownBy(() -> registry.register("AAPL", null, Optional.empty(), Optional.empty()))
                .isInstanceOf(UnsupportedExchangeException.class);
    }

    private static final class SequencedUuidGenerator implements UuidGenerator {
        private final AtomicLong counter = new AtomicLong(0);

        @Override
        public UUID newUuid() {
            long lsb = counter.incrementAndGet();
            return new UUID(0L, lsb);
        }
    }
}
