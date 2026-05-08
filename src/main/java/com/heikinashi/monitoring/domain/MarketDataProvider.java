package com.heikinashi.monitoring.domain;

import java.time.Instant;
import java.util.List;

/**
 * External market-data port (CLAUDE.md §6). Implementations live in
 * {@code infrastructure}.
 *
 * <p>Other methods (quote info, earnings, news, recommendations,
 * financials, insider transactions) listed in CLAUDE.md are best-effort
 * Block 6 dependencies and will be added when the AI tool surface lands.
 *
 * <p>Implementations may raise:
 * <ul>
 *   <li>{@link com.heikinashi.monitoring.domain.error.TickerNotFoundException}
 *       when the symbol is unknown / delisted at the provider.</li>
 *   <li>{@link com.heikinashi.monitoring.domain.error.ProviderUnavailableException}
 *       on transient failure (auth/captcha/timeout/5xx) after the adapter's
 *       internal retries.</li>
 *   <li>{@link com.heikinashi.monitoring.domain.error.SchemaDriftException}
 *       when the response cannot be parsed.</li>
 * </ul>
 */
public interface MarketDataProvider {

    /**
     * Fetch raw historical bars for {@code symbol} on {@code tf} since
     * {@code since} (inclusive). Bar times are raw — the caller is
     * responsible for normalization and closed-bar filtering.
     */
    List<OHLCBar> fetchHistory(String symbol, Timeframe tf, Instant since);
}
