package com.heikinashi.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InstrumentTest {

    private static final Instant T0 = Instant.parse("2026-05-07T22:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-08T22:00:00Z");

    @Test
    void rejects_null_required_fields() {
        assertThatThrownBy(() -> new Instrument(
                        null, "AAPL", "NASDAQ", Optional.empty(), Optional.empty(), InstrumentStatus.ACTIVE, T0, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void with_status_preserves_creation_timestamp() {
        Instrument original = new Instrument(
                "id-1", "AAPL", "NASDAQ", Optional.empty(), Optional.empty(), InstrumentStatus.ACTIVE, T0, T0);

        Instrument archived = original.withStatus(InstrumentStatus.ARCHIVED, T1);

        assertThat(archived.status()).isEqualTo(InstrumentStatus.ARCHIVED);
        assertThat(archived.createdAt()).isEqualTo(T0);
        assertThat(archived.updatedAt()).isEqualTo(T1);
        assertThat(archived.ticker()).isEqualTo("AAPL");
    }

    @Test
    void with_metadata_does_not_change_id_or_immutable_fields() {
        Instrument original = new Instrument(
                "id-1", "AAPL", "NASDAQ", Optional.empty(), Optional.empty(), InstrumentStatus.ACTIVE, T0, T0);

        Instrument updated = original.withMetadata(Optional.of("Apple"), Optional.of("USD"), T1);

        assertThat(updated.id()).isEqualTo("id-1");
        assertThat(updated.ticker()).isEqualTo("AAPL");
        assertThat(updated.exchange()).isEqualTo("NASDAQ");
        assertThat(updated.name()).contains("Apple");
        assertThat(updated.currency()).contains("USD");
        assertThat(updated.updatedAt()).isEqualTo(T1);
    }

    @Test
    void status_wire_round_trip() {
        assertThat(InstrumentStatus.ACTIVE.wire()).isEqualTo("active");
        assertThat(InstrumentStatus.ARCHIVED.wire()).isEqualTo("archived");
        assertThat(InstrumentStatus.fromWire("active")).isEqualTo(InstrumentStatus.ACTIVE);
        assertThat(InstrumentStatus.fromWire("archived")).isEqualTo(InstrumentStatus.ARCHIVED);
        assertThatThrownBy(() -> InstrumentStatus.fromWire("foo")).isInstanceOf(IllegalArgumentException.class);
    }
}
