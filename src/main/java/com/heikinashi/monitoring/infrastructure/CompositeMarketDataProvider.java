package com.heikinashi.monitoring.infrastructure;

import com.heikinashi.monitoring.domain.MarketDataProvider;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.infrastructure.eodhd.EodhdMarketDataProvider;
import com.heikinashi.monitoring.infrastructure.marketaux.MarketauxNewsProvider;
import io.micronaut.context.annotation.Primary;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;

/**
 * The {@link MarketDataProvider} the rest of the app injects. Composes the
 * single-purpose adapters: OHLC history from EODHD, news headlines from
 * Marketaux. The remaining fundamentals methods (quote info, earnings,
 * recommendations, financials, insider transactions) keep the interface's
 * empty defaults — no provider implements them yet.
 *
 * <p>{@code @Primary} resolves the injection ambiguity with
 * {@link EodhdMarketDataProvider}, which is also a {@code MarketDataProvider}
 * bean.
 */
@Singleton
@Primary
public class CompositeMarketDataProvider implements MarketDataProvider {

    private final EodhdMarketDataProvider history;
    private final MarketauxNewsProvider news;

    public CompositeMarketDataProvider(EodhdMarketDataProvider history, MarketauxNewsProvider news) {
        this.history = history;
        this.news = news;
    }

    @Override
    public List<OHLCBar> fetchHistory(String symbol, Timeframe tf, Instant since) {
        return history.fetchHistory(symbol, tf, since);
    }

    @Override
    public List<NewsHeadline> fetchNewsHeadlines(String ticker, String exchange, int max) {
        return news.fetchNewsHeadlines(ticker, exchange, max);
    }
}
