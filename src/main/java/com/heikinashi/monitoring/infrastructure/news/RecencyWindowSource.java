package com.heikinashi.monitoring.infrastructure.news;

import com.heikinashi.monitoring.domain.Timeframe;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The per-timeframe look-back window news adapters scope their queries with —
 * a daily signal wants very recent news, a weekly one tolerates an older
 * window. {@code MarketauxConfig} is the single implementation: Block 17
 * requires every news provider to derive the SAME window the Marketaux
 * adapter uses ({@code published_after} there, {@code from}/{@code to} for
 * EODHD), so the values live in one place and the derivation in one method.
 */
public interface RecencyWindowSource {

    /** The look-back window, in days, for the given pattern timeframe. */
    int recencyDays(Timeframe tf);

    /** The earliest publication date (UTC) a news query should accept. */
    default LocalDate publishedAfter(Clock clock, Timeframe tf) {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(recencyDays(tf));
    }
}
