package com.heikinashi.monitoring.domain.error;

import java.util.HashMap;
import java.util.Map;

/**
 * Surfaces SES configuration errors that are not recoverable in-process —
 * the sender is unverified, sandbox mode is still in effect in production,
 * the account's sending privilege has been paused, or the deploy role
 * lacks {@code ses:SendEmail} on the identity.
 *
 * <p>Per CLAUDE.md §3 these are {@link InternalException} so they bubble
 * out of the dispatch flow to the Lambda DLQ; retrying inside the same
 * run will never help.
 */
public final class SESConfigurationException extends InternalException {
    public SESConfigurationException(String reason, Throwable cause) {
        super("SES_CONFIGURATION", "SES configuration error: " + reason, payload(reason), cause);
    }

    private static Map<String, Object> payload(String reason) {
        Map<String, Object> p = new HashMap<>();
        p.put("reason", reason);
        return p;
    }
}
