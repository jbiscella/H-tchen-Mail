package com.heikinashi.monitoring.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/** Pure helpers for timeframe arithmetic (CLAUDE.md §6). */
public final class Timeframes {

    public static final long ONE_DAY_SECONDS = 86_400L;
    public static final long ONE_WEEK_SECONDS = 604_800L;

    private Timeframes() {}

    public static long periodSeconds(Timeframe tf) {
        return switch (tf) {
            case D1 -> ONE_DAY_SECONDS;
            case W1 -> ONE_WEEK_SECONDS;
        };
    }

    /** {@code 1d → date midnight UTC; 1w → Monday 00:00 UTC of that week.} */
    public static Instant normalizeBarTime(Instant raw, Timeframe tf) {
        LocalDate date = raw.atOffset(ZoneOffset.UTC).toLocalDate();
        return switch (tf) {
            case D1 -> date.atStartOfDay().toInstant(ZoneOffset.UTC);
            case W1 -> {
                LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield LocalDateTime.of(monday, java.time.LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC);
            }
        };
    }

    /** {@code bar_time + period_seconds(tf) <= now}. */
    public static boolean isClosed(Instant barTime, Timeframe tf, Instant now) {
        return !barTime.plusSeconds(periodSeconds(tf)).isAfter(now);
    }
}
