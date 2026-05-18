package com.heikinashi.monitoring.infrastructure.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiConfidence;
import com.heikinashi.monitoring.domain.error.LLMException;
import org.junit.jupiter.api.Test;

class AiAnalysisJsonTest {

    @Test
    void parses_a_well_formed_payload() {
        String raw = """
                {
                  "corroborating": "Earnings beat last quarter; positive news flow.",
                  "contradicting": "Sector beta is high; insider selling reported.",
                  "confidence": "MEDIUM",
                  "data_sources": ["news_headlines(5)", "recommendations(0)"]
                }
                """;
        AiAnalysis a = AiAnalysisJson.parse(raw);
        assertThat(a.confidence()).isEqualTo(AiConfidence.MEDIUM);
        assertThat(a.corroborating()).contains("Earnings beat last quarter; positive news flow.");
        assertThat(a.contradicting()).contains("Sector beta is high; insider selling reported.");
        assertThat(a.dataSources()).containsExactly("news_headlines(5)", "recommendations(0)");
    }

    @Test
    void tolerates_surrounding_chatter_and_strips_to_the_object() {
        String raw = "Sure, here you go: { \"confidence\": \"LOW\", \"data_sources\": [] } that's it.";
        AiAnalysis a = AiAnalysisJson.parse(raw);
        assertThat(a.confidence()).isEqualTo(AiConfidence.LOW);
    }

    @Test
    void treats_empty_strings_as_absent_optional_fields() {
        String raw = "{\"corroborating\":\"\",\"contradicting\":\"\",\"confidence\":\"HIGH\",\"data_sources\":[]}";
        AiAnalysis a = AiAnalysisJson.parse(raw);
        assertThat(a.corroborating()).isEmpty();
        assertThat(a.contradicting()).isEmpty();
        assertThat(a.confidence()).isEqualTo(AiConfidence.HIGH);
    }

    @Test
    void flattens_a_structured_field_into_prose_instead_of_java_tostring() {
        // A model that ignores the "plain prose" instruction and answers with an
        // array of objects must not leak "[{headline=...}]" into the email.
        String raw = """
                {
                  "corroborating": [
                    {"headline": "Earnings beat", "why": "supports the bullish reversal"},
                    {"headline": "Upbeat guidance", "why": "confirms momentum"}
                  ],
                  "contradicting": "Sector beta is high.",
                  "confidence": "MEDIUM",
                  "data_sources": []
                }
                """;
        AiAnalysis a = AiAnalysisJson.parse(raw);
        assertThat(a.corroborating()).isPresent();
        assertThat(a.corroborating().get())
                .doesNotContain("[{")
                .doesNotContain("headline=")
                .contains("Earnings beat")
                .contains("supports the bullish reversal")
                .contains("Upbeat guidance");
    }

    @Test
    void empty_input_raises_LLMException() {
        assertThatThrownBy(() -> AiAnalysisJson.parse("")).isInstanceOf(LLMException.class);
        assertThatThrownBy(() -> AiAnalysisJson.parse("   ")).isInstanceOf(LLMException.class);
    }

    @Test
    void non_json_output_raises_LLMException() {
        assertThatThrownBy(() -> AiAnalysisJson.parse("plain prose without any json"))
                .isInstanceOf(LLMException.class);
    }

    @Test
    void missing_confidence_raises_LLMException() {
        assertThatThrownBy(() -> AiAnalysisJson.parse("{\"corroborating\":\"x\"}"))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void unknown_confidence_raises_LLMException() {
        assertThatThrownBy(() -> AiAnalysisJson.parse("{\"confidence\":\"MAYBE\"}"))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("MAYBE");
    }
}
