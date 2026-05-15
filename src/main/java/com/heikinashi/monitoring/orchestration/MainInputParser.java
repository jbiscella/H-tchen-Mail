package com.heikinashi.monitoring.orchestration;

import com.heikinashi.monitoring.domain.MainInput;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parses the raw {@code Map<String, Object>} payload AWS Lambda hands the
 * handler into a typed {@link MainInput}. Extracted from {@code MonitoringMainHandler}
 * so the parsing semantics (boolean-vs-string coercion, missing-field defaults,
 * empty-collection handling) can be unit-tested without spinning up the
 * Micronaut context.
 */
final class MainInputParser {

    private MainInputParser() {}

    static MainInput parse(Map<String, Object> input) {
        if (input == null) {
            return MainInput.allActive();
        }
        boolean forceEmail = parseBoolean(input.get("force_email"));
        Object ids = input.get("instrument_ids");
        MainInput base;
        if (ids instanceof Collection<?> col && !col.isEmpty()) {
            Set<String> set = new LinkedHashSet<>();
            for (Object id : col) {
                if (id != null) set.add(id.toString());
            }
            base = MainInput.forInstruments(set);
        } else {
            base = MainInput.allActive();
        }
        return forceEmail ? base.withForceEmail(true) : base;
    }

    /**
     * Accepts the boolean {@code true}/{@code false} as well as the string
     * forms {@code "true"}/{@code "false"} (case-insensitive). Lambda's JSON
     * deserialiser may give either depending on whether the test event has
     * the field quoted.
     */
    static boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return false;
        return Boolean.parseBoolean(value.toString());
    }
}
