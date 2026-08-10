package com.heikinashi.monitoring.infrastructure.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heikinashi.monitoring.application.InMemoryHaRepository;
import com.heikinashi.monitoring.application.InMemoryMarketDataProvider;
import com.heikinashi.monitoring.application.InMemoryOhlcRepository;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiConfidence;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.HaRepository;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.LLMException;
import com.heikinashi.monitoring.domain.strategy.Strategy;
import com.heikinashi.monitoring.domain.strategy.StrategyAlert;
import com.heikinashi.monitoring.domain.strategy.StrategyAlertLine;
import com.heikinashi.monitoring.domain.strategy.StrategyScenario;
import com.heikinashi.monitoring.infrastructure.chart.ChartConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

class BedrockAiAnalystTest {

    private static final PatternEvent EVENT = new PatternEvent(
            "abc-123",
            "AAPL",
            "NASDAQ",
            Timeframe.D1,
            Instant.parse("2026-05-06T00:00:00Z"),
            PatternKind.COLOR_CHANGE,
            PatternSubtype.BULLISH_REVERSAL,
            Map.of(),
            new BarSnapshot(
                    new BigDecimal("100"),
                    new BigDecimal("110"),
                    new BigDecimal("95"),
                    new BigDecimal("105"),
                    Optional.empty(),
                    new BigDecimal("100"),
                    new BigDecimal("110"),
                    new BigDecimal("95"),
                    new BigDecimal("105")),
            Instant.parse("2026-05-07T22:00:00Z"));

    /** A minimal valid END_TURN payload for tests whose subject is the request, not the reply. */
    private static final String ANALYSIS_JSON = "{\"confidence\":\"HIGH\",\"data_sources\":[]}";

    /** Strategy-flow counterpart of {@link #EVENT}, for the raw-series assertions. */
    private static final StrategyAlert STRATEGY_ALERT = new StrategyAlert(
            "abc-123",
            "AAPL",
            "NASDAQ",
            Timeframe.D1,
            Instant.parse("2026-05-06T00:00:00Z"),
            "demo-strategy",
            List.of(new StrategyAlertLine(
                    "enter long", "long_entry", Optional.empty(), Optional.empty(), Optional.empty())),
            Instant.parse("2026-05-07T22:00:00Z"));

    @Test
    void single_END_TURN_response_yields_AiAnalysis() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        scripted.next(endTurnWithText("{\"corroborating\":\"strong earnings\",\"contradicting\":\"no analyst "
                + "ratings returned — would show institutional backing\",\"confidence\":\"HIGH\","
                + "\"data_sources\":[\"news_headlines(5)\",\"recommendations(0)\"]}"));

        BedrockAiAnalyst analyst =
                new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), emptyContext());
        AiAnalysis result = analyst.analyze(EVENT);
        assertThat(result.confidence()).isEqualTo(AiConfidence.HIGH);
        assertThat(result.corroborating()).contains("strong earnings");
        verify(client, times(1)).converse(any(ConverseRequest.class));
    }

    @Test
    void TOOL_USE_then_END_TURN_runs_the_tool_then_returns_analysis() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        scripted.next(toolUse(
                "tu-1",
                "get_quote_info",
                Document.fromMap(Map.of(
                        "ticker", Document.fromString("AAPL"),
                        "exchange", Document.fromString("NASDAQ")))));
        scripted.next(endTurnWithText("{\"confidence\":\"MEDIUM\",\"data_sources\":[\"quote_info(1)\"]}"));

        BedrockAiAnalyst analyst =
                new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), emptyContext());
        AiAnalysis result = analyst.analyze(EVENT);
        assertThat(result.confidence()).isEqualTo(AiConfidence.MEDIUM);
        verify(client, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void system_prompt_pins_the_RELEVANCE_triage_block() {
        // Block 17 prompt contract: the relevance triage lives in the prompt, so it
        // is verified at the snapshot level — a regression that drops the block
        // must fail CI. The three pinned behaviours: items are candidates (loose
        // multi-ticker tagging), promotional/incidental items are discarded and
        // never featured, and an empty post-triage set is said explicitly.
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        scripted.next(endTurnWithText("{\"confidence\":\"LOW\",\"data_sources\":[]}"));

        BedrockAiAnalyst analyst =
                new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), emptyContext());
        analyst.analyze(EVENT);

        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(captor.capture());
        String system = captor.getValue().system().get(0).text();
        assertThat(system).contains("RELEVANCE");
        assertThat(system).containsIgnoringCase("candidates");
        assertThat(system).containsIgnoringCase("promotional");
        assertThat(system.indexOf("RELEVANCE")).isLessThan(system.indexOf("CORROBORATING"));
        // Readability increment: the FORMAT block must ask for blank-line
        // paragraph breaks between distinct stories (still banning lists),
        // expressed as the escaped \n\n sequence — never a raw line break,
        // which would invalidate the JSON (Codex P2 on PR #85).
        assertThat(system).containsIgnoringCase("blank line");
        assertThat(system).contains("\\n\\n");
    }

    @Test
    void MAX_TOKENS_response_raises_LLMException() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        scripted.next(stopWith(StopReason.MAX_TOKENS, "{}"));
        BedrockAiAnalyst analyst =
                new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), emptyContext());
        assertThatThrownBy(() -> analyst.analyze(EVENT))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("MAX_TOKENS");
    }

    @Test
    void cap_reached_forces_a_final_call_without_tools() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        // 2 tool-use iterations, then a forced final wrap-up.
        scripted.next(toolUse(
                "tu-1",
                "get_news_headlines",
                Document.fromMap(Map.of(
                        "ticker", Document.fromString("AAPL"),
                        "exchange", Document.fromString("NASDAQ")))));
        scripted.next(toolUse(
                "tu-2",
                "get_news_headlines",
                Document.fromMap(Map.of(
                        "ticker", Document.fromString("AAPL"),
                        "exchange", Document.fromString("NASDAQ")))));
        scripted.next(endTurnWithText("{\"confidence\":\"LOW\",\"data_sources\":[]}"));

        BedrockAiAnalyst analyst =
                new BedrockAiAnalyst(client, configWithCap(2), new InMemoryMarketDataProvider(), emptyContext());
        AiAnalysis result = analyst.analyze(EVENT);
        assertThat(result.confidence()).isEqualTo(AiConfidence.LOW);
        verify(client, times(3)).converse(any(ConverseRequest.class));
    }

    @Test
    void invalid_JSON_in_END_TURN_raises_LLMException() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        scripted.next(endTurnWithText("this is not json"));
        BedrockAiAnalyst analyst =
                new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), emptyContext());
        assertThatThrownBy(() -> analyst.analyze(EVENT)).isInstanceOf(LLMException.class);
    }

    // -------- Block 18: technical-context block ------------------------------

    @Test
    void the_user_message_carries_the_charts_lookback_series() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));

        new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), contextWithBars(30))
                .analyze(EVENT);

        String user = capturedUserText(client);
        assertThat(user).contains("Chart context");
        assertThat(user).contains("30 Heikin-Ashi bars on 1d");
        assertThat(user).contains("ha_open  ha_high  ha_low  ha_close  colour");
        // The alert bar's raw OHLC still comes from the event snapshot.
        assertThat(user).contains("OHLC values: open=100");
    }

    @Test
    void the_serialized_indicators_match_the_charts_resolved_set() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));

        new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), contextWithBars(30))
                .analyze(EVENT);

        String user = capturedUserText(client);
        // ChartConfig defaults: sma-period 10, ema-period 20, show-rsi true, rsi-period 14.
        assertThat(user).contains("SMA(10) = ");
        assertThat(user).contains("EMA(20) = ");
        assertThat(user).contains("RSI(14) = ");
    }

    @Test
    void an_indicator_disabled_in_config_appears_in_neither_chart_nor_message() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));
        ChartConfig config = chartConfig();
        config.setSmaPeriod(0); // disables the SMA overlay
        config.setShowRsi(false); // disables the RSI sub-pane

        new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), contextWith(30, config))
                .analyze(EVENT);

        String user = capturedUserText(client);
        assertThat(user).doesNotContain("SMA(");
        assertThat(user).doesNotContain("RSI(");
        assertThat(user).contains("EMA(20) = ");
    }

    @Test
    void an_indicator_whose_period_exceeds_the_window_is_omitted() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));
        ChartConfig config = chartConfig();
        config.setEmaPeriod(200); // the renderer skips this overlay on a 30-bar window

        new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), contextWith(30, config))
                .analyze(EVENT);

        // Pins the observable outcome — an overlay the chart does not draw is not described
        // — rather than one mechanism. Mutation testing showed the omission is enforced at
        // three independent layers: the explicit minBars skip, the path floor in line(),
        // and dsl-eval raising a warmup error which valueAt() swallows. Disabling the skip
        // alone does not make this fail, which is defence in depth rather than dead
        // coverage: the assertion still fails if all three were to break.
        assertThat(capturedUserText(client)).doesNotContain("EMA(200)");
    }

    @Test
    void indicator_values_are_computed_over_the_ha_closes_of_the_window() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));
        ChartConfig config = chartConfig();
        config.setSmaPeriod(3);
        config.setEmaPeriod(0);
        config.setShowRsi(false);
        // The last three bars carry ha_close 128, 129, 130, so SMA(3) is 129.
        //
        // Note this pins the arithmetic and the window, NOT the choice of ha_close over
        // raw close: the builder reads HABar, which has no raw close, so there is no
        // alternative basis for a test to distinguish. That the values match the drawn
        // overlays follows structurally from mapping ha_close into the close slot before
        // evaluation (see TechnicalContextBuilder) — it is not observable here.
        new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), contextWith(30, config))
                .analyze(EVENT);

        assertThat(capturedUserText(client)).contains("SMA(3) = 129");
    }

    @Test
    void a_context_failure_degrades_the_note_but_never_the_alert() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));
        HaRepository failing = Mockito.mock(HaRepository.class);
        when(failing.findLastNBefore(any(), any(), any(), Mockito.anyInt()))
                .thenThrow(new IllegalStateException("repository down"));
        TechnicalContextBuilder exploding =
                new TechnicalContextBuilder(failing, new InMemoryOhlcRepository(), chartConfig());

        AiAnalysis result = new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), exploding)
                .analyze(EVENT);

        assertThat(result.confidence()).isEqualTo(AiConfidence.HIGH);
        assertThat(capturedUserText(client)).doesNotContain("Chart context");
    }

    // --- Codex PR #86 review fixes ---------------------------------------------

    @Test
    void an_indicator_whose_period_equals_the_window_is_still_evaluated() {
        // Codex P2: `from = max(minBars, size - PATH_POINTS)` made the loop run zero times
        // when the window was exactly minBars, so SMA(30) on a 30-bar lookback appeared on
        // the chart (which accepts bars == minBars) but not in the note.
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));
        ChartConfig config = chartConfig();
        config.setSmaPeriod(30);
        config.setEmaPeriod(0);
        config.setShowRsi(false);

        new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), contextWith(30, config))
                .analyze(EVENT);

        assertThat(capturedUserText(client)).contains("SMA(30) = ");
    }

    @Test
    void the_triggering_bar_is_restored_when_retention_removed_it() {
        // Codex P2: under SNAPSHOT_ONLY a retried alert's HA bar may be gone. The chart
        // repairs it from the event snapshot; the context must agree, or the two disagree
        // about whether the alert bar exists.
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));
        // Empty HA repository = retention removed everything, including the alert bar.
        TechnicalContextBuilder context =
                new TechnicalContextBuilder(new InMemoryHaRepository(), new InMemoryOhlcRepository(), chartConfig());

        new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), context).analyze(EVENT);

        String user = capturedUserText(client);
        assertThat(user).contains("Chart context");
        assertThat(user).contains("1 Heikin-Ashi bars");
        assertThat(user).contains(EVENT.barTime().toString());
    }

    @Test
    void a_strategy_alert_uses_the_raw_ohlc_series_not_the_ha_series() {
        // Codex P1: the strategy chart is a RAW OHLC chart with PriceSource.CLOSE
        // indicators, so serving this flow from the HA series printed values disagreeing
        // with both the chart and the condition that fired.
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));

        InMemoryOhlcRepository ohlc = new InMemoryOhlcRepository();
        for (int i = 1; i <= 30; i++) {
            BigDecimal close = new BigDecimal(200 + i);
            ohlc.putBar(
                    new com.heikinashi.monitoring.domain.OHLCBar(
                            STRATEGY_ALERT.instrumentId(),
                            STRATEGY_ALERT.timeframe(),
                            STRATEGY_ALERT.barTime().minusSeconds((30 - i) * 86400L),
                            close.subtract(BigDecimal.ONE),
                            close.add(BigDecimal.ONE),
                            close.subtract(BigDecimal.ONE),
                            close,
                            Optional.empty(),
                            "test",
                            EVENT.detectedAt()),
                    Optional.empty());
        }
        // An HA repository that would answer with different numbers if it were consulted.
        TechnicalContextBuilder context = new TechnicalContextBuilder(new InMemoryHaRepository(), ohlc, chartConfig());

        new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), context)
                .analyze(STRATEGY_ALERT);

        String user = capturedUserText(client);
        assertThat(user).contains("raw OHLC bars");
        assertThat(user).contains("bar_time  open  high  low  close");
        assertThat(user).doesNotContain("ha_close");
    }

    @Test
    void a_strategy_alert_derives_indicators_from_the_passed_strategy_not_a_lookup() {
        // Codex P2: re-looking-up the strategy by instrument id let a re-import between
        // dispatch and this call swap it, so the note could describe indicators the attached
        // chart does not draw. The strategy is now a parameter and the builder holds no
        // repository, so the note and the chart describe the same instance by construction.
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));

        InMemoryOhlcRepository ohlc = new InMemoryOhlcRepository();
        for (int i = 1; i <= 40; i++) {
            BigDecimal close = new BigDecimal(200 + i);
            ohlc.putBar(
                    new com.heikinashi.monitoring.domain.OHLCBar(
                            STRATEGY_ALERT.instrumentId(),
                            STRATEGY_ALERT.timeframe(),
                            STRATEGY_ALERT.barTime().minusSeconds((40 - i) * 86400L),
                            close.subtract(BigDecimal.ONE),
                            close.add(BigDecimal.ONE),
                            close.subtract(BigDecimal.ONE),
                            close,
                            Optional.empty(),
                            "test",
                            EVENT.detectedAt()),
                    Optional.empty());
        }
        Strategy passed = new Strategy(
                "passed-strategy",
                List.of(new StrategyScenario(
                        "enter long",
                        "long_entry",
                        List.of("rsi(20) is below 30"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));

        new BedrockAiAnalyst(
                        client,
                        configWithCap(8),
                        new InMemoryMarketDataProvider(),
                        new TechnicalContextBuilder(new InMemoryHaRepository(), ohlc, chartConfig()))
                .analyze(STRATEGY_ALERT, passed);

        assertThat(capturedUserText(client)).contains("RSI(20) = ");
    }

    @Test
    void the_degraded_overload_reports_the_series_without_indicators() {
        // Strategy deleted since detection: the send is already chart-degraded, so the note
        // carries price action but must not invent an indicator set.
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        new ScriptedClient(client).next(endTurnWithText(ANALYSIS_JSON));

        InMemoryOhlcRepository ohlc = new InMemoryOhlcRepository();
        ohlc.putBar(
                new com.heikinashi.monitoring.domain.OHLCBar(
                        STRATEGY_ALERT.instrumentId(),
                        STRATEGY_ALERT.timeframe(),
                        STRATEGY_ALERT.barTime(),
                        new BigDecimal("100"),
                        new BigDecimal("101"),
                        new BigDecimal("99"),
                        new BigDecimal("100"),
                        Optional.empty(),
                        "test",
                        EVENT.detectedAt()),
                Optional.empty());

        new BedrockAiAnalyst(
                        client,
                        configWithCap(8),
                        new InMemoryMarketDataProvider(),
                        new TechnicalContextBuilder(new InMemoryHaRepository(), ohlc, chartConfig()))
                .analyze(STRATEGY_ALERT);

        String user = capturedUserText(client);
        assertThat(user).contains("raw OHLC bars");
        assertThat(user).doesNotContain("Indicator values");
    }

    /** The USER-role text of the first captured request. */
    private static String capturedUserText(BedrockRuntimeClient client) {
        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client, atLeastOnce()).converse(captor.capture());
        return captor.getAllValues().get(0).messages().get(0).content().get(0).text();
    }

    private static ChartConfig chartConfig() {
        return new ChartConfig();
    }

    /** A context builder over an empty HA repository — yields no block, so pre-Block-18 assertions hold. */
    private static TechnicalContextBuilder emptyContext() {
        return new TechnicalContextBuilder(new InMemoryHaRepository(), new InMemoryOhlcRepository(), chartConfig());
    }

    private static TechnicalContextBuilder contextWithBars(int bars) {
        return contextWith(bars, chartConfig());
    }

    /**
     * A context builder over {@code bars} synthetic HA bars ending at the event bar. HA
     * closes ramp 101..(100+bars) while the HA opens trail them, so every bar is green and
     * the last three closes are deterministic for the HA-close assertion above.
     */
    private static TechnicalContextBuilder contextWith(int bars, ChartConfig config) {
        InMemoryHaRepository repo = new InMemoryHaRepository();
        for (int i = 1; i <= bars; i++) {
            BigDecimal close = new BigDecimal(100 + i);
            BigDecimal open = new BigDecimal(100 + i).subtract(BigDecimal.ONE);
            repo.putBar(
                    new HABar(
                            EVENT.instrumentId(),
                            EVENT.timeframe(),
                            EVENT.barTime().minusSeconds((bars - i) * 86400L),
                            open,
                            close,
                            open,
                            close,
                            EVENT.detectedAt()),
                    Optional.empty());
        }
        return new TechnicalContextBuilder(repo, new InMemoryOhlcRepository(), config);
    }

    // -------- helpers --------------------------------------------------------

    private static BedrockConfig configWithCap(int cap) {
        BedrockConfig config = new BedrockConfig();
        config.setMaxToolIterations(cap);
        config.setMaxTokens(500);
        return config;
    }

    private static ConverseResponse endTurnWithText(String text) {
        return stopWith(StopReason.END_TURN, text);
    }

    private static ConverseResponse stopWith(StopReason reason, String text) {
        Message m = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText(text))
                .build();
        return ConverseResponse.builder()
                .stopReason(reason)
                .output(ConverseOutput.builder().message(m).build())
                .build();
    }

    private static ConverseResponse toolUse(String toolUseId, String toolName, Document input) {
        Message m = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(List.of(ContentBlock.fromToolUse(ToolUseBlock.builder()
                        .toolUseId(toolUseId)
                        .name(toolName)
                        .input(input)
                        .build())))
                .build();
        return ConverseResponse.builder()
                .stopReason(StopReason.TOOL_USE)
                .output(ConverseOutput.builder().message(m).build())
                .build();
    }

    /** Wraps a Mockito mock so successive responses can be queued in declaration order. */
    private static final class ScriptedClient {
        private final Deque<ConverseResponse> queue = new ArrayDeque<>();

        ScriptedClient(BedrockRuntimeClient client) {
            when(client.converse(any(ConverseRequest.class))).thenAnswer(inv -> {
                if (queue.isEmpty()) {
                    throw new AssertionError("ScriptedClient ran out of responses");
                }
                return queue.pollFirst();
            });
        }

        void next(ConverseResponse response) {
            queue.add(response);
        }
    }
}
