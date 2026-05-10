package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A pattern event whose enrichment failed, queued for retry (CLAUDE.md §2 / §9).
 *
 * <p>Identified by a deterministic {@code eventUid =
 * <instrument_id>_<tf>_<bar_time>_<pattern>_<subtype>} so that races between
 * concurrent retry pollers see the same item.
 */
public record PendingAlert(
        String eventUid, PatternEvent event, int retryCount, Instant retryAt, LastError lastError, Instant createdAt) {

    public PendingAlert {
        Objects.requireNonNull(eventUid, "eventUid");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(retryAt, "retryAt");
        Objects.requireNonNull(lastError, "lastError");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public PendingAlert bumped(Instant nextRetryAt, LastError newError) {
        return new PendingAlert(eventUid, event, retryCount + 1, nextRetryAt, newError, createdAt);
    }

    public static String uidOf(PatternEvent event) {
        return event.instrumentId()
                + "_" + event.timeframe().wire()
                + "_" + event.barTime()
                + "_" + event.pattern().wire()
                + "_" + event.subtype().wire();
    }

    public record LastError(String code, String message, Instant ts, Optional<String> componentFailed) {
        public LastError {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(ts, "ts");
            Objects.requireNonNull(componentFailed, "componentFailed");
        }
    }
}
