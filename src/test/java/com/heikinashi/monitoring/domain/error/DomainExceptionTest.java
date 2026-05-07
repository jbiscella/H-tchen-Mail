package com.heikinashi.monitoring.domain.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainExceptionTest {

    @Test
    void carries_code_message_and_payload() {
        DomainException e = new ValidationException("X_TEST", "msg", Map.of("k", "v")) {};
        assertThat(e.code()).isEqualTo("X_TEST");
        assertThat(e.getMessage()).isEqualTo("msg");
        assertThat(e.payload()).containsEntry("k", "v");
    }

    @Test
    void supports_cause_via_transient_constructor() {
        IOException cause = new IOException("boom");
        TransientException e = new TransientException("X_TRANSIENT", "wrap", Map.of(), cause) {};
        assertThat(e.code()).isEqualTo("X_TRANSIENT");
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void supports_cause_via_internal_constructor() {
        IllegalStateException cause = new IllegalStateException("nope");
        InternalException e = new InternalException("X_INTERNAL", "wrap", Map.of(), cause) {};
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void rejects_null_code() {
        assertThatThrownBy(() -> new ValidationException(null, "m", Map.of()) {})
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void each_block_1_exception_exposes_expected_code() {
        assertThat(new InvalidTickerException("ticker", "").code()).isEqualTo("INVALID_TICKER");
        assertThat(new UnsupportedExchangeException("X", java.util.Set.of("NASDAQ")).code())
                .isEqualTo("UNSUPPORTED_EXCHANGE");
        assertThat(new ImmutableFieldException("ticker").code()).isEqualTo("IMMUTABLE_FIELD");
        assertThat(new InstrumentNotFoundException("id-1").code()).isEqualTo("INSTRUMENT_NOT_FOUND");
        assertThat(new DuplicateInstrumentException("AAPL", "NASDAQ").code()).isEqualTo("DUPLICATE_INSTRUMENT");
    }
}
