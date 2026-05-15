package com.heikinashi.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.MainInput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainInputParserTest {

    @Test
    void null_payload_means_all_active_no_force() {
        MainInput in = MainInputParser.parse(null);
        assertThat(in.instrumentIds()).isEmpty();
        assertThat(in.forceEmail()).isFalse();
    }

    @Test
    void empty_payload_means_all_active_no_force() {
        MainInput in = MainInputParser.parse(Map.of());
        assertThat(in.instrumentIds()).isEmpty();
        assertThat(in.forceEmail()).isFalse();
    }

    @Test
    void instrument_ids_only_keeps_force_email_false() {
        MainInput in = MainInputParser.parse(Map.of("instrument_ids", List.of("a", "b")));
        assertThat(in.instrumentIds()).contains(java.util.Set.of("a", "b"));
        assertThat(in.forceEmail()).isFalse();
    }

    @Test
    void force_email_boolean_true_is_recognised() {
        MainInput in = MainInputParser.parse(Map.of("force_email", true));
        assertThat(in.forceEmail()).isTrue();
        assertThat(in.instrumentIds()).isEmpty();
    }

    @Test
    void force_email_string_true_is_recognised() {
        // Some JSON consumers stringify boolean fields (e.g. when the test
        // event editor in the AWS console quotes the value); accept both.
        MainInput in = MainInputParser.parse(Map.of("force_email", "true"));
        assertThat(in.forceEmail()).isTrue();
    }

    @Test
    void force_email_string_TRUE_case_insensitive() {
        MainInput in = MainInputParser.parse(Map.of("force_email", "TRUE"));
        assertThat(in.forceEmail()).isTrue();
    }

    @Test
    void force_email_string_false_yields_false() {
        MainInput in = MainInputParser.parse(Map.of("force_email", "false"));
        assertThat(in.forceEmail()).isFalse();
    }

    @Test
    void force_email_nonsense_string_yields_false() {
        // Boolean.parseBoolean returns false for anything that isn't "true".
        // That's the desired behaviour: garbage = no force, no exception.
        MainInput in = MainInputParser.parse(Map.of("force_email", "yes"));
        assertThat(in.forceEmail()).isFalse();
    }

    @Test
    void force_email_combined_with_instrument_ids() {
        MainInput in = MainInputParser.parse(Map.of("instrument_ids", List.of("u-1"), "force_email", true));
        assertThat(in.instrumentIds()).contains(java.util.Set.of("u-1"));
        assertThat(in.forceEmail()).isTrue();
    }

    @Test
    void empty_instrument_ids_collection_falls_back_to_all_active() {
        MainInput in = MainInputParser.parse(Map.of("instrument_ids", List.of()));
        assertThat(in.instrumentIds()).isEmpty();
    }

    @Test
    void null_value_in_instrument_ids_is_skipped() {
        Map<String, Object> payload = new HashMap<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        ids.add("u-1");
        ids.add(null);
        ids.add("u-2");
        payload.put("instrument_ids", ids);
        MainInput in = MainInputParser.parse(payload);
        assertThat(in.instrumentIds()).contains(java.util.Set.of("u-1", "u-2"));
    }
}
