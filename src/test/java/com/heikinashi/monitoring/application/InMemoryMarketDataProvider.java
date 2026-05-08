package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.MarketDataProvider;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.ProviderUnavailableException;
import com.heikinashi.monitoring.domain.error.SchemaDriftException;
import com.heikinashi.monitoring.domain.error.TickerNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test fake. Pre-canned bar list per (symbol, timeframe). Specific symbols can
 * be flagged to raise transient/not-found/schema-drift errors so the
 * exception-handling scenarios can be exercised.
 */
public final class InMemoryMarketDataProvider implements MarketDataProvider {

    private final Map<String, List<OHLCBar>> historyBySymbol = new HashMap<>();
    private final Map<String, RuntimeException> failBySymbol = new HashMap<>();
    private final Map<String, Integer> failCountdownBySymbol = new HashMap<>();
    private final List<String> calledSymbols = new ArrayList<>();

    public void primeHistory(String symbol, Timeframe tf, List<OHLCBar> bars) {
        historyBySymbol.put(key(symbol, tf), List.copyOf(bars));
    }

    public void primeNotFound(String symbol) {
        failBySymbol.put(symbol, new TickerNotFoundException(symbol, ""));
    }

    public void primeProviderUnavailable(String symbol) {
        failBySymbol.put(symbol, new ProviderUnavailableException("yahoo", new RuntimeException("boom")));
    }

    public void primeSchemaDrift(String symbol) {
        failBySymbol.put(symbol, new SchemaDriftException("test.endpoint", "{}"));
    }

    /** Fail the next {@code n} calls for {@code symbol} (transient), then resume normal history. */
    public void primeTransientFor(String symbol, int n) {
        failCountdownBySymbol.put(symbol, n);
    }

    public List<String> calledSymbols() {
        return List.copyOf(calledSymbols);
    }

    @Override
    public List<OHLCBar> fetchHistory(String symbol, Timeframe tf, Instant since) {
        calledSymbols.add(symbol);

        Integer remaining = failCountdownBySymbol.get(symbol);
        if (remaining != null && remaining > 0) {
            failCountdownBySymbol.put(symbol, remaining - 1);
            throw new ProviderUnavailableException("yahoo", new RuntimeException("transient"));
        }

        RuntimeException pre = failBySymbol.get(symbol);
        if (pre != null) {
            throw pre;
        }

        List<OHLCBar> bars =
                Optional.ofNullable(historyBySymbol.get(key(symbol, tf))).orElseGet(List::of);
        List<OHLCBar> filtered = new ArrayList<>();
        for (OHLCBar b : bars) {
            if (!b.barTime().isBefore(since)) {
                filtered.add(b);
            }
        }
        filtered.sort(Comparator.comparing(OHLCBar::barTime));
        return filtered;
    }

    private static String key(String symbol, Timeframe tf) {
        return symbol + "#" + tf.wire();
    }
}
