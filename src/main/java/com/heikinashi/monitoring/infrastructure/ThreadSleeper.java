package com.heikinashi.monitoring.infrastructure;

import com.heikinashi.monitoring.application.Sleeper;
import jakarta.inject.Singleton;

/** Default {@link Sleeper}: a real {@link Thread#sleep(long)}. */
@Singleton
public class ThreadSleeper implements Sleeper {

    @Override
    public void sleepMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while backing off", e);
        }
    }
}
