package com.heikinashi.monitoring.infrastructure.dynamodb;

/**
 * Single-table key conventions per CLAUDE.md §2.
 *
 * <p>Centralised here so adapters never spell key strings inline.
 */
final class Keys {
    private Keys() {}

    static final String INSTRUMENT_PK_PREFIX = "INSTRUMENT#";
    static final String TICKER_LOCK_PK_PREFIX = "TICKER#";
    static final String PENDING_ALERT_PK_PREFIX = "PENDING_ALERT#";

    static final String SK_META = "META";
    static final String SK_CONFIG = "CONFIG";
    static final String SK_LOCK = "LOCK";
    static final String SK_ALERT_PREFIX = "ALERT#";

    static final String ENTITY_INSTRUMENT = "INSTRUMENT";
    static final String ENTITY_CONFIG = "CONFIG";
    static final String ENTITY_LOCK = "UNIQUE_LOCK";
    static final String ENTITY_PENDING_ALERT = "PENDING_ALERT";
    static final String ENTITY_ALERT = "ALERT";

    static final String GSI1_PK_PREFIX_STATUS = "STATUS#";
    static final String GSI1_SK_PREFIX_INSTRUMENT = "INSTRUMENT#";
    static final String GSI2_PK_RETRY_DUE = "RETRY_DUE";

    static String instrumentPk(String id) {
        return INSTRUMENT_PK_PREFIX + id;
    }

    static String tickerLockPk(String exchange, String ticker) {
        return TICKER_LOCK_PK_PREFIX + exchange + "#" + ticker;
    }

    static String pendingAlertPk(String eventUid) {
        return PENDING_ALERT_PK_PREFIX + eventUid;
    }

    /** {@code ALERT#<bar_time_iso>#<pattern>#<subtype>#<sent_at_ms>} (CLAUDE.md §2). */
    static String alertSk(String barTimeIso, String pattern, String subtype, long sentAtMs) {
        return SK_ALERT_PREFIX + barTimeIso + "#" + pattern + "#" + subtype + "#" + sentAtMs;
    }

    static String statusGsi1Pk(String wireStatus) {
        return GSI1_PK_PREFIX_STATUS + wireStatus;
    }

    static String instrumentGsi1Sk(String id) {
        return GSI1_SK_PREFIX_INSTRUMENT + id;
    }
}
