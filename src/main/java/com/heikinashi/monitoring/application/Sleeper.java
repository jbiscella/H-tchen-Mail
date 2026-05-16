package com.heikinashi.monitoring.application;

/**
 * Injectable pause primitive. Retry backoff needs a real wait in production but
 * none in tests — CLAUDE.md §13 bans {@code Thread.sleep} in tests because it
 * makes them slow and flaky. Production binds a real sleeper; tests pass a
 * no-op so the retry path runs at full speed.
 */
@FunctionalInterface
public interface Sleeper {

    void sleepMillis(long millis);
}
