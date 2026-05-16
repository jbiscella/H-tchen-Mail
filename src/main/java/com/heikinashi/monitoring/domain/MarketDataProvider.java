package com.heikinashi.monitoring.domain;

import com.heikinashi.monitoring.domain.fundamentals.AnalystRating;
import com.heikinashi.monitoring.domain.fundamentals.EarningsCalendar;
import com.heikinashi.monitoring.domain.fundamentals.InsiderTransaction;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.domain.fundamentals.QuarterFigures;
import com.heikinashi.monitoring.domain.fundamentals.QuoteInfo;
import java.time.Instant;
import java.util.List;

/**
 * External market-data port (CLAUDE.md §6 / §9). Implementations live in
 * {@code infrastructure}. Fundamentals queries (the non-history methods) are
 * best-effort: implementations may return empty results when the underlying
 * provider lacks the data, and the AI analyst tool layer treats empty results
 * gracefully rather than failing the whole alert.
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

    default QuoteInfo fetchQuoteInfo(String ticker, String exchange) {
        return QuoteInfo.empty();
    }

    default EarningsCalendar fetchEarningsCalendar(String ticker, String exchange) {
        return EarningsCalendar.empty();
    }

    /**
     * Recent news headlines for the instrument. {@code tf} is the pattern's
     * timeframe — providers that scope recency to the signal's horizon (e.g.
     * Marketaux's {@code published_after} filter) use it; others ignore it.
     */
    default List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max, Timeframe tf) {
        return List.of();
    }

    default List<AnalystRating> fetchRecommendations(String ticker, String exchange) {
        return List.of();
    }

    default List<QuarterFigures> fetchFinancialsSummary(String ticker, String exchange) {
        return List.of();
    }

    default List<InsiderTransaction> fetchInsiderTransactions(String ticker, String exchange) {
        return List.of();
    }
}
