package com.heikinashi.monitoring.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record Page<T>(List<T> items, Optional<String> nextCursor) {
    public Page {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(nextCursor, "nextCursor");
        items = List.copyOf(items);
    }
}
