package com.heikinashi.monitoring.infrastructure.bedrock;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiAnalyst;
import com.heikinashi.monitoring.domain.MarketDataProvider;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.error.LLMException;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

/**
 * {@link AiAnalyst} backed by Amazon Bedrock's Converse API + a manual
 * tool-use loop (CLAUDE.md §9).
 *
 * <p>Loop semantics:
 * <ol>
 *   <li>Send the system + user prompt with the {@link ToolCatalog}'s
 *       tool configuration.</li>
 *   <li>If {@code stopReason == TOOL_USE}: execute every tool_use block
 *       locally, append a user-role message of {@code toolResult} blocks,
 *       and re-send.</li>
 *   <li>If {@code stopReason == END_TURN}: parse the text content as JSON
 *       and return.</li>
 *   <li>If {@code stopReason == MAX_TOKENS}: surface as {@link LLMException}.</li>
 *   <li>If the configured iteration cap is hit, force one final
 *       {@link BedrockRuntimeClient#converse(ConverseRequest)} call
 *       <em>without</em> a tool config so the model is forced to wrap up.</li>
 * </ol>
 */
@Singleton
public class BedrockAiAnalyst implements AiAnalyst {

    private static final Logger LOG = LoggerFactory.getLogger(BedrockAiAnalyst.class);

    private static final String SYSTEM_PROMPT = """
            You are a financial analyst writing the fundamental-analysis note attached to a \
            detected Heikin-Ashi pattern alert. Use the tools to fetch the data you judge \
            relevant, then produce JSON ONLY with this schema:
            {"corroborating": "...", "contradicting": "...", "confidence": "LOW|MEDIUM|HIGH", \
            "data_sources": ["news_headlines(5)", "recommendations(0)", ...]}

            FORMAT — "corroborating" and "contradicting" must each be a single string of \
            plain prose: flowing sentences an analyst would write. They must NOT be JSON, \
            arrays, objects, key=value pairs, or bullet/numbered lists. Mention the items \
            inline, in the prose. Only "confidence" is an enum and "data_sources" an array \
            of strings.

            CORROBORATING — discuss up to 5 news items that justify the detected pattern, \
            in flowing prose. Rank which items to feature by, in priority order:
              1. Recency — items published within 7 days of the pattern bar_time come first.
              2. Direction alignment — items whose content or sentiment matches the pattern \
            direction (bullish_* subtypes are bullish, bearish_* subtypes are bearish).
              3. Specificity — items naming the company directly outrank sector-wide news.
              4. Material impact — earnings, M&A, guidance and regulatory news outrank \
            analyst opinion, which outranks generic sector news.
            Name each item you feature and say in a sentence why it supports the signal. \
            Discuss fewer than 5 if fewer pass the filters; if none are relevant, say so \
            explicitly rather than padding the note with noise.

            CONTRADICTING — note fundamentals that weaken or fail to support the signal. \
            When a useful data source returned nothing, do not just write "data not \
            available": briefly explain why that specific data would have mattered for THIS \
            pattern (for example, analyst ratings would show whether a bullish reversal has \
            institutional backing).

            DATA_SOURCES — list every tool you called, formatted as "name(count)" where \
            count is how many items it returned, e.g. "news_headlines(5)", \
            "recommendations(0)". This makes source coverage transparent to the reader.

            CONFIDENCE — your own judgment of how strongly the evidence you actually \
            gathered supports the pattern. Weigh how material and relevant the news is, \
            not how much of it there is: sparse but highly material evidence can still be \
            HIGH, abundant but weak evidence can be LOW. You decide.

            Be honest about limited information; never invent facts. There is no strict \
            length limit — prioritise information density over brevity. Output the JSON \
            object only, with no prose outside it.""";

    private final BedrockRuntimeClient client;
    private final BedrockConfig config;
    private final MarketDataProvider provider;

    public BedrockAiAnalyst(BedrockRuntimeClient client, BedrockConfig config, MarketDataProvider provider) {
        this.client = client;
        this.config = config;
        this.provider = provider;
    }

    @Override
    public AiAnalysis analyze(PatternEvent event) {
        try {
            return runLoop(event);
        } catch (LLMException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LLMException("Bedrock invocation failed", e);
        }
    }

    private AiAnalysis runLoop(PatternEvent event) {
        // The tool catalog is per-event: news tools scope recency to the
        // pattern's timeframe (CLAUDE.md §9 / Marketaux published_after).
        ToolCatalog catalog = new ToolCatalog(provider, event.timeframe());
        List<Message> messages = new ArrayList<>();
        messages.add(buildUserMessage(event));

        for (int i = 0; i < config.getMaxToolIterations(); i++) {
            ConverseResponse resp = client.converse(buildRequest(messages, true, catalog));
            Message assistant = resp.output().message();
            messages.add(assistant);
            StopReason stop = resp.stopReason();
            if (stop == StopReason.END_TURN) {
                return parseFinalAnalysis(assistant);
            }
            if (stop == StopReason.MAX_TOKENS) {
                throw new LLMException("MAX_TOKENS reached before final answer");
            }
            if (stop == StopReason.TOOL_USE) {
                messages.add(buildToolResultsMessage(assistant, catalog));
                continue;
            }
            throw new LLMException("Unexpected Bedrock stop reason: " + stop);
        }

        // Iteration cap hit: force a final response without toolConfig.
        LOG.warn("bedrock_tool_iteration_cap_reached cap={}", config.getMaxToolIterations());
        ConverseResponse forced = client.converse(buildRequest(messages, false, catalog));
        Message forcedMessage = forced.output().message();
        if (forced.stopReason() == StopReason.MAX_TOKENS) {
            throw new LLMException("MAX_TOKENS reached on forced wrap-up");
        }
        return parseFinalAnalysis(forcedMessage);
    }

    private ConverseRequest buildRequest(List<Message> messages, boolean withTools, ToolCatalog catalog) {
        ConverseRequest.Builder b = ConverseRequest.builder()
                .modelId(config.getModelId())
                .system(SystemContentBlock.fromText(SYSTEM_PROMPT))
                .messages(messages)
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(config.getMaxTokens())
                        .build());
        if (withTools) {
            b.toolConfig(catalog.toolConfiguration());
        }
        return b.build();
    }

    private Message buildUserMessage(PatternEvent event) {
        String prompt = "Pattern detected:\n"
                + "  instrument: " + event.ticker() + " on " + event.exchange() + "\n"
                + "  timeframe: " + event.timeframe().wire() + "\n"
                + "  bar_time: " + event.barTime() + "\n"
                + "  pattern: " + event.pattern().wire() + " / "
                + event.subtype().wire() + "\n"
                + "  HA values: ha_open=" + event.barSnapshot().haOpen()
                + ", ha_close=" + event.barSnapshot().haClose()
                + ", ha_high=" + event.barSnapshot().haHigh()
                + ", ha_low=" + event.barSnapshot().haLow() + "\n"
                + "  OHLC values: open=" + event.barSnapshot().open()
                + ", high=" + event.barSnapshot().high()
                + ", low=" + event.barSnapshot().low()
                + ", close=" + event.barSnapshot().close() + "\n"
                + "Decide which tools to call, then write the note as JSON only.";
        return Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(prompt))
                .build();
    }

    private Message buildToolResultsMessage(Message assistant, ToolCatalog catalog) {
        List<ContentBlock> results = new ArrayList<>();
        for (ContentBlock block : assistant.content()) {
            ToolUseBlock toolUse = block.toolUse();
            if (toolUse == null) {
                continue;
            }
            ToolResultBlock.Builder result = ToolResultBlock.builder().toolUseId(toolUse.toolUseId());
            if (catalog.knows(toolUse.name())) {
                result.content(ToolResultContentBlock.fromJson(catalog.invoke(toolUse.name(), toolUse.input())));
            } else {
                result.content(ToolResultContentBlock.fromText("Unknown tool: " + toolUse.name()));
            }
            results.add(ContentBlock.fromToolResult(result.build()));
        }
        return Message.builder().role(ConversationRole.USER).content(results).build();
    }

    private AiAnalysis parseFinalAnalysis(Message assistant) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : assistant.content()) {
            if (block.text() != null) {
                text.append(block.text());
            }
        }
        return AiAnalysisJson.parse(text.toString());
    }
}
