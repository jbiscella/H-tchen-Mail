package com.heikinashi.monitoring.domain;

import java.util.Objects;

/** PNG bytes + dimensions emitted by the chart renderer (CLAUDE.md §9). */
public record ChartImage(byte[] bytes, String contentType, int widthPx, int heightPx) {

    public ChartImage {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(contentType, "contentType");
    }
}
