package com.heikinashi.monitoring.infrastructure.yahoo;

import com.heikinashi.monitoring.domain.MarketDataProvider;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.error.TickerNotFoundException;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

/**
 * {@link MarketDataProvider} backed by {@code de.sfuhrm:YahooFinanceAPI}.
 * Translates the library's {@link IOException} surface into the domain's
 * transient/not-found/schema-drift exception classes (CLAUDE.md §6).
 */
@Singleton
public class YahooFinanceProvider implements MarketDataProvider {

    private static final Logger LOG = LoggerFactory.getLogger(YahooFinanceProvider.class);
    private static final String PROVIDER = "yahoo";

    private final Clock clock;

    public YahooFinanceProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<OHLCBar> fetchHistory(String symbol, Timeframe tf, Instant since) {
        Calendar from = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        from.setTime(Date.from(since));
        Calendar to = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

        Stock stock;
        try {
            stock = YahooFinance.get(symbol, from, to, intervalFor(tf));
        } catch (IOException e) {
            throw new ProviderUnavailableException(PROVIDER, e);
        }

        if (stock == null || !stock.isValid()) {
            throw new TickerNotFoundException(symbol, "");
        }

        List<HistoricalQuote> rawHistory;
        try {
            rawHistory = stock.getHistory();
        } catch (IOException e) {
            throw new ProviderUnavailableException(PROVIDER, e);
        }
        if (rawHistory == null) {
            throw new SchemaDriftException("yahoo.history", "null history list for symbol " + symbol);
        }

        Instant ingestedAt = clock.instant();
        List<OHLCBar> out = new ArrayList<>(rawHistory.size());
        for (HistoricalQuote q : rawHistory) {
            try {
                if (q.getDate() == null
                        || q.getOpen() == null
                        || q.getHigh() == null
                        || q.getLow() == null
                        || q.getClose() == null) {
                    LOG.debug("yahoo_skipping_bar symbol={} reason=missing_field", symbol);
                    continue;
                }
                out.add(new OHLCBar(
                        "",
                        tf,
                        q.getDate().toInstant(),
                        q.getOpen(),
                        q.getHigh(),
                        q.getLow(),
                        q.getClose(),
                        Optional.ofNullable(q.getVolume()).map(java.math.BigDecimal::valueOf),
                        PROVIDER,
                        ingestedAt));
            } catch (RuntimeException e) {
                throw new SchemaDriftException(
                        "yahoo.history", "could not map bar for " + symbol + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static Interval intervalFor(Timeframe tf) {
        return switch (tf) {
            case D1 -> Interval.DAILY;
            case W1 -> Interval.WEEKLY;
        };
    }
}
