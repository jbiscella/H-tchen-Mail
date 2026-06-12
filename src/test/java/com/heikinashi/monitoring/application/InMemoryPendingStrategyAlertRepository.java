package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.PendingStrategyAlert;
import com.heikinashi.monitoring.domain.PendingStrategyAlertRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fake for {@link PendingStrategyAlertRepository}. Mirrors idempotent enqueue + conditional bump. */
public final class InMemoryPendingStrategyAlertRepository implements PendingStrategyAlertRepository {

    private final Map<String, PendingStrategyAlert> byUid = new HashMap<>();
    private boolean failNextWrite;

    /** Scripts the next {@link #enqueue} to throw, like a DynamoDB PutItem outage. */
    public void failNextWrite() {
        this.failNextWrite = true;
    }

    @Override
    public void enqueue(PendingStrategyAlert pending) {
        if (failNextWrite) {
            failNextWrite = false;
            throw new RuntimeException("scripted pending-strategy PutItem failure");
        }
        byUid.putIfAbsent(pending.eventUid(), pending);
    }

    @Override
    public Optional<PendingStrategyAlert> findByUid(String eventUid) {
        return Optional.ofNullable(byUid.get(eventUid));
    }

    @Override
    public List<PendingStrategyAlert> queryDue(Instant now, int limit) {
        List<PendingStrategyAlert> due = new ArrayList<>();
        for (PendingStrategyAlert a : byUid.values()) {
            if (!a.retryAt().isAfter(now)) {
                due.add(a);
            }
        }
        due.sort(Comparator.comparing(PendingStrategyAlert::retryAt));
        return due.size() <= limit ? due : new ArrayList<>(due.subList(0, limit));
    }

    @Override
    public boolean bumpRetry(PendingStrategyAlert updated, int expectedRetryCount) {
        PendingStrategyAlert existing = byUid.get(updated.eventUid());
        if (existing == null) return false;
        if (existing.retryCount() != expectedRetryCount) {
            return false;
        }
        byUid.put(updated.eventUid(), updated);
        return true;
    }

    @Override
    public void delete(String eventUid) {
        byUid.remove(eventUid);
    }

    public int size() {
        return byUid.size();
    }
}
