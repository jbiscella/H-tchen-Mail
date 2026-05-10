package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.PendingAlert;
import com.heikinashi.monitoring.domain.PendingAlertRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fake. Mirrors the conditional-update bump and idempotent enqueue. */
public final class InMemoryPendingAlertRepository implements PendingAlertRepository {

    private final Map<String, PendingAlert> byUid = new HashMap<>();

    @Override
    public void enqueue(PendingAlert pending) {
        byUid.putIfAbsent(pending.eventUid(), pending);
    }

    @Override
    public Optional<PendingAlert> findByUid(String eventUid) {
        return Optional.ofNullable(byUid.get(eventUid));
    }

    @Override
    public List<PendingAlert> queryDue(Instant now, int limit) {
        List<PendingAlert> due = new ArrayList<>();
        for (PendingAlert a : byUid.values()) {
            if (!a.retryAt().isAfter(now)) {
                due.add(a);
            }
        }
        due.sort(Comparator.comparing(PendingAlert::retryAt));
        return due.size() <= limit ? due : new ArrayList<>(due.subList(0, limit));
    }

    @Override
    public boolean bumpRetry(PendingAlert updated, int expectedRetryCount) {
        PendingAlert existing = byUid.get(updated.eventUid());
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
