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
import com.heikinashi.monitoring.application.InMemoryStrategyRepository;
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
                new TechnicalContextBuilder(failing, chartConfig(), new InMemoryStrategyRepository());

        AiAnalysis result = new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider(), exploding)
                .analyze(EVENT);

        assertThat(result.confidence()).isEqualTo(AiConfidence.HIGH);
        assertThat(capturedUserText(client)).doesNotContain("Chart context");
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
        return new TechnicalContextBuilder(new InMemoryHaRepository(), chartConfig(), new InMemoryStrategyRepository());
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
        return new TechnicalContextBuilder(repo, config, new InMemoryStrategyRepository());
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
