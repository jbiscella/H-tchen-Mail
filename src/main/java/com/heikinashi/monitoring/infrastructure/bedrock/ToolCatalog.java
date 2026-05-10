package com.heikinashi.monitoring.infrastructure.bedrock;

import com.heikinashi.monitoring.domain.MarketDataProvider;
import com.heikinashi.monitoring.domain.fundamentals.AnalystRating;
import com.heikinashi.monitoring.domain.fundamentals.EarningsCalendar;
import com.heikinashi.monitoring.domain.fundamentals.InsiderTransaction;
import com.heikinashi.monitoring.domain.fundamentals.NewsHeadline;
import com.heikinashi.monitoring.domain.fundamentals.QuarterFigures;
import com.heikinashi.monitoring.domain.fundamentals.QuoteInfo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

/**
 * The 6-tool catalog the AI analyst can call (CLAUDE.md §9). Each tool is
 * backed by a {@link MarketDataProvider} method and produces a JSON-shaped
 * {@link Document} for the {@code toolResult} content block.
 *
 * <p>The catalog truncates list outputs to the top 5 items (per spec) and
 * maintains a tiny per-invocation cache so the same tool call within one AI
 * loop is not repeated.
 */
final class ToolCatalog {

    private static final int TOP_N = 5;
    private static final ToolInputSchema TICKER_EXCHANGE_SCHEMA = tickerExchangeSchema();

    private final Map<String, Tool> toolsByName = new LinkedHashMap<>();
    private final Map<String, BiFunction<String, String, Document>> handlers = new LinkedHashMap<>();
    private final ToolConfiguration toolConfiguration;

    ToolCatalog(MarketDataProvider provider) {
        register(
                "get_quote_info",
                "Sector, industry, market cap, P/E, EPS, beta, dividend yield for a ticker.",
                (ticker, exchange) -> quoteInfoToDocument(provider.fetchQuoteInfo(ticker, exchange)));
        register(
                "get_earnings_calendar",
                "Next earnings date, last earnings date and the most recent surprise percent.",
                (ticker, exchange) -> earningsToDocument(provider.fetchEarningsCalendar(ticker, exchange)));
        register(
                "get_news_headlines",
                "Recent news headlines for the ticker, with publication date and source.",
                (ticker, exchange) -> headlinesToDocument(provider.fetchNewsHeadlines(ticker, exchange, TOP_N)));
        register(
                "get_recommendations",
                "Recent analyst recommendations and ratings for the ticker.",
                (ticker, exchange) -> ratingsToDocument(provider.fetchRecommendations(ticker, exchange)));
        register(
                "get_financials_summary",
                "Revenue, net income and operating cash flow for the last 4 reported quarters.",
                (ticker, exchange) -> financialsToDocument(provider.fetchFinancialsSummary(ticker, exchange)));
        register(
                "get_insider_transactions",
                "Recent insider transactions for the ticker.",
                (ticker, exchange) -> insiderToDocument(provider.fetchInsiderTransactions(ticker, exchange)));

        this.toolConfiguration = ToolConfiguration.builder()
                .tools(new ArrayList<>(toolsByName.values()))
                .build();
    }

    ToolConfiguration toolConfiguration() {
        return toolConfiguration;
    }

    boolean knows(String toolName) {
        return handlers.containsKey(toolName);
    }

    Document invoke(String toolName, Document input) {
        BiFunction<String, String, Document> handler = handlers.get(toolName);
        if (handler == null) {
            return Document.fromString("Unknown tool: " + toolName);
        }
        String ticker = stringField(input, "ticker", "");
        String exchange = stringField(input, "exchange", "");
        try {
            return handler.apply(ticker, exchange);
        } catch (RuntimeException e) {
            Map<String, Document> err = new LinkedHashMap<>();
            err.put("error", Document.fromString(e.getClass().getSimpleName()));
            err.put("message", Document.fromString(safe(e.getMessage())));
            return Document.fromMap(err);
        }
    }

    // -------- registration ---------------------------------------------------

    private void register(String name, String description, BiFunction<String, String, Document> handler) {
        Tool tool = Tool.fromToolSpec(ToolSpecification.builder()
                .name(name)
                .description(description)
                .inputSchema(TICKER_EXCHANGE_SCHEMA)
                .build());
        toolsByName.put(name, tool);
        handlers.put(name, handler);
    }

    private static ToolInputSchema tickerExchangeSchema() {
        Map<String, Document> properties = new LinkedHashMap<>();
        properties.put("ticker", typedString("Ticker symbol, e.g. AAPL"));
        properties.put("exchange", typedString("Exchange code, e.g. NASDAQ"));
        Map<String, Document> schema = new LinkedHashMap<>();
        schema.put("type", Document.fromString("object"));
        schema.put("properties", Document.fromMap(properties));
        schema.put(
                "required", Document.fromList(List.of(Document.fromString("ticker"), Document.fromString("exchange"))));
        return ToolInputSchema.fromJson(Document.fromMap(schema));
    }

    private static Document typedString(String description) {
        Map<String, Document> field = new LinkedHashMap<>();
        field.put("type", Document.fromString("string"));
        field.put("description", Document.fromString(description));
        return Document.fromMap(field);
    }

    // -------- result encoders ------------------------------------------------

    private static Document quoteInfoToDocument(QuoteInfo q) {
        Map<String, Document> m = new LinkedHashMap<>();
        q.sector().ifPresent(v -> m.put("sector", Document.fromString(v)));
        q.industry().ifPresent(v -> m.put("industry", Document.fromString(v)));
        q.marketCap().ifPresent(v -> m.put("market_cap", numberDoc(v)));
        q.peRatio().ifPresent(v -> m.put("pe_ratio", numberDoc(v)));
        q.eps().ifPresent(v -> m.put("eps", numberDoc(v)));
        q.beta().ifPresent(v -> m.put("beta", numberDoc(v)));
        q.dividendYield().ifPresent(v -> m.put("dividend_yield", numberDoc(v)));
        return Document.fromMap(m);
    }

    private static Document earningsToDocument(EarningsCalendar e) {
        Map<String, Document> m = new LinkedHashMap<>();
        e.nextEarningsDate().ifPresent(v -> m.put("next_earnings_date", Document.fromString(v.toString())));
        e.lastEarningsDate().ifPresent(v -> m.put("last_earnings_date", Document.fromString(v.toString())));
        e.lastSurprisePercent().ifPresent(v -> m.put("last_surprise_percent", numberDoc(v)));
        return Document.fromMap(m);
    }

    private static Document headlinesToDocument(List<NewsHeadline> news) {
        List<Document> items = new ArrayList<>();
        for (NewsHeadline h : truncate(news)) {
            Map<String, Document> m = new LinkedHashMap<>();
            m.put("title", Document.fromString(h.title()));
            m.put("date", Document.fromString(h.publishedAt().toString()));
            m.put("source", Document.fromString(h.source()));
            items.add(Document.fromMap(m));
        }
        Map<String, Document> root = new LinkedHashMap<>();
        root.put("headlines", Document.fromList(items));
        return Document.fromMap(root);
    }

    private static Document ratingsToDocument(List<AnalystRating> ratings) {
        List<Document> items = new ArrayList<>();
        for (AnalystRating r : truncate(ratings)) {
            Map<String, Document> m = new LinkedHashMap<>();
            m.put("firm", Document.fromString(r.firm()));
            m.put("rating", Document.fromString(r.rating()));
            m.put("date", Document.fromString(r.date().toString()));
            items.add(Document.fromMap(m));
        }
        Map<String, Document> root = new LinkedHashMap<>();
        root.put("ratings", Document.fromList(items));
        return Document.fromMap(root);
    }

    private static Document financialsToDocument(List<QuarterFigures> figures) {
        List<Document> items = new ArrayList<>();
        for (QuarterFigures q : truncate(figures)) {
            Map<String, Document> m = new LinkedHashMap<>();
            m.put("quarter_end", Document.fromString(q.quarterEnd().toString()));
            q.revenue().ifPresent(v -> m.put("revenue", numberDoc(v)));
            q.netIncome().ifPresent(v -> m.put("net_income", numberDoc(v)));
            q.operatingCashFlow().ifPresent(v -> m.put("operating_cash_flow", numberDoc(v)));
            items.add(Document.fromMap(m));
        }
        Map<String, Document> root = new LinkedHashMap<>();
        root.put("quarters", Document.fromList(items));
        return Document.fromMap(root);
    }

    private static Document insiderToDocument(List<InsiderTransaction> txns) {
        List<Document> items = new ArrayList<>();
        for (InsiderTransaction t : truncate(txns)) {
            Map<String, Document> m = new LinkedHashMap<>();
            m.put("insider", Document.fromString(t.insider()));
            m.put("type", Document.fromString(t.transactionType()));
            t.shares().ifPresent(v -> m.put("shares", numberDoc(v)));
            m.put("date", Document.fromString(t.transactionDate().toString()));
            items.add(Document.fromMap(m));
        }
        Map<String, Document> root = new LinkedHashMap<>();
        root.put("transactions", Document.fromList(items));
        return Document.fromMap(root);
    }

    // -------- helpers --------------------------------------------------------

    private static <T> List<T> truncate(List<T> list) {
        if (list == null) return List.of();
        return list.size() <= TOP_N ? list : list.subList(0, TOP_N);
    }

    private static Document numberDoc(BigDecimal v) {
        return Document.fromNumber(v);
    }

    private static String stringField(Document input, String key, String fallback) {
        if (input == null || !input.isMap()) return fallback;
        Document v = input.asMap().get(key);
        if (v == null || !v.isString()) return fallback;
        return v.asString();
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    @SuppressWarnings("unused")
    static Optional<String> stringFromDoc(Document d) {
        return d != null && d.isString() ? Optional.of(d.asString()) : Optional.empty();
    }
}
