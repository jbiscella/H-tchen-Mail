package com.heikinashi.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.error.OHLCInvariantViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OHLCBarTest {

    private static final Instant T = Instant.parse("2026-05-06T00:00:00Z");

    @Test
    void valid_bar_passes_invariant_check() {
        bar(open("100"), high("110"), low("95"), close("105")).validateInvariants();
    }

    @Test
    void rejects_high_below_low() {
        assertThatThrownBy(() ->
                        bar(open("100"), high("90"), low("95"), close("92")).validateInvariants())
                .isInstanceOf(OHLCInvariantViolationException.class)
                .extracting(t -> ((OHLCInvariantViolationException) t).payload().get("field"))
                .isEqualTo("high<low");
    }

    @Test
    void rejects_high_below_open_or_close() {
        assertThatThrownBy(() ->
                        bar(open("105"), high("100"), low("95"), close("102")).validateInvariants())
                .isInstanceOf(OHLCInvariantViolationException.class);
        assertThatThrownBy(() ->
                        bar(open("100"), high("104"), low("95"), close("105")).validateInvariants())
                .isInstanceOf(OHLCInvariantViolationException.class);
    }

    @Test
    void rejects_low_above_open_or_close() {
        assertThatThrownBy(() ->
                        bar(open("90"), high("110"), low("95"), close("105")).validateInvariants())
                .isInstanceOf(OHLCInvariantViolationException.class);
        assertThatThrownBy(() ->
                        bar(open("100"), high("110"), low("95"), close("90")).validateInvariants())
                .isInstanceOf(OHLCInvariantViolationException.class);
    }

    @Test
    void rejects_non_positive_prices() {
        assertThatThrownBy(() ->
                        bar(open("0"), high("110"), low("95"), close("105")).validateInvariants())
                .isInstanceOf(OHLCInvariantViolationException.class);
        assertThatThrownBy(() ->
                        bar(open("100"), high("110"), low("0"), close("105")).validateInvariants())
                .isInstanceOf(OHLCInvariantViolationException.class);
    }

    @Test
    void with_helpers_preserve_other_fields() {
        OHLCBar b = bar(open("100"), high("110"), low("95"), close("105"));
        assertThat(b.withInstrumentId("new-id").instrumentId()).isEqualTo("new-id");
        assertThat(b.withBarTime(T.plusSeconds(86_400)).barTime()).isEqualTo(T.plusSeconds(86_400));
    }

    private static OHLCBar bar(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        return new OHLCBar("inst-1", Timeframe.D1, T, open, high, low, close, Optional.empty(), "test", T);
    }

    private static BigDecimal open(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal high(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal low(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal close(String v) {
        return new BigDecimal(v);
    }
}
