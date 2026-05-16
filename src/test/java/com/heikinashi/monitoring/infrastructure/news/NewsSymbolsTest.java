package com.heikinashi.monitoring.infrastructure.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NewsSymbolsTest {

    private static final Map<String, String> MAP =
            Map.of("NASDAQ", "", "NYSE", "", "SWX", ".SW", "LSE", ".L", "XETRA", ".DE");

    @Test
    void us_listing_gets_the_bare_ticker() {
        assertThat(NewsSymbols.forExchange("AAPL", "NASDAQ", MAP)).isEqualTo("AAPL");
    }

    @Test
    void swiss_listing_gets_the_dot_sw_suffix() {
        assertThat(NewsSymbols.forExchange("CFR", "SWX", MAP)).isEqualTo("CFR.SW");
    }

    @Test
    void london_and_xetra_use_the_common_suffixes_not_eodhd_ones() {
        assertThat(NewsSymbols.forExchange("BP", "LSE", MAP)).isEqualTo("BP.L");
        assertThat(NewsSymbols.forExchange("SAP", "XETRA", MAP)).isEqualTo("SAP.DE");
    }

    @Test
    void unknown_exchange_falls_back_to_the_bare_ticker() {
        assertThat(NewsSymbols.forExchange("XYZ", "NOWHERE", MAP)).isEqualTo("XYZ");
    }

    @Test
    void parses_the_suffix_map_json() {
        Map<String, String> parsed = NewsSymbols.parseSuffixMap("{\"SWX\":\".SW\",\"NASDAQ\":\"\"}");
        assertThat(parsed).containsEntry("SWX", ".SW").containsEntry("NASDAQ", "");
    }

    @Test
    void empty_json_object_parses_to_an_empty_map() {
        assertThat(NewsSymbols.parseSuffixMap("{}")).isEmpty();
    }

    @Test
    void malformed_json_raises() {
        assertThatThrownBy(() -> NewsSymbols.parseSuffixMap("not json")).isInstanceOf(IllegalStateException.class);
    }
}
