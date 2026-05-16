package com.heikinashi.monitoring.infrastructure.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heikinashi.monitoring.application.InMemoryMarketDataProvider;
import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiConfidence;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.LLMException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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

    @Test
    void single_END_TURN_response_yields_AiAnalysis() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        scripted.next(endTurnWithText("{\"corroborating\":\"strong earnings\",\"contradicting\":\"no analyst "
                + "ratings returned — would show institutional backing\",\"confidence\":\"HIGH\","
                + "\"data_sources\":[\"news_headlines(5)\",\"recommendations(0)\"]}"));

        BedrockAiAnalyst analyst = new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider());
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

        BedrockAiAnalyst analyst = new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider());
        AiAnalysis result = analyst.analyze(EVENT);
        assertThat(result.confidence()).isEqualTo(AiConfidence.MEDIUM);
        verify(client, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void MAX_TOKENS_response_raises_LLMException() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        scripted.next(stopWith(StopReason.MAX_TOKENS, "{}"));
        BedrockAiAnalyst analyst = new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider());
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

        BedrockAiAnalyst analyst = new BedrockAiAnalyst(client, configWithCap(2), new InMemoryMarketDataProvider());
        AiAnalysis result = analyst.analyze(EVENT);
        assertThat(result.confidence()).isEqualTo(AiConfidence.LOW);
        verify(client, times(3)).converse(any(ConverseRequest.class));
    }

    @Test
    void invalid_JSON_in_END_TURN_raises_LLMException() {
        BedrockRuntimeClient client = Mockito.mock(BedrockRuntimeClient.class);
        ScriptedClient scripted = new ScriptedClient(client);
        scripted.next(endTurnWithText("this is not json"));
        BedrockAiAnalyst analyst = new BedrockAiAnalyst(client, configWithCap(8), new InMemoryMarketDataProvider());
        assertThatThrownBy(() -> analyst.analyze(EVENT)).isInstanceOf(LLMException.class);
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
