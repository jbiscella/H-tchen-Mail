package com.heikinashi.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.LLMException;
import org.junit.jupiter.api.Test;

class DispatchFailureStageTest {

    @Test
    void dependency_unavailable_short_circuits_regardless_of_component() {
        DependencyUnavailableException e = new DependencyUnavailableException("ses", new RuntimeException());
        assertThat(DispatchFailureStage.of("ai", e)).isEqualTo("dependency_unavailable");
        assertThat(DispatchFailureStage.of("chart", e)).isEqualTo("dependency_unavailable");
        assertThat(DispatchFailureStage.of("email", e)).isEqualTo("dependency_unavailable");
    }

    @Test
    void ai_LLMException_with_no_cause_classified_by_message() {
        assertThat(DispatchFailureStage.of("ai", new LLMException("MAX_TOKENS reached before final answer")))
                .isEqualTo("max_tokens");
        assertThat(DispatchFailureStage.of("ai", new LLMException("Unexpected Bedrock stop reason: GUARDRAIL")))
                .isEqualTo("unexpected_stop");
        assertThat(DispatchFailureStage.of("ai", new LLMException("AI analyst returned non-JSON output")))
                .isEqualTo("response_non_json");
        assertThat(DispatchFailureStage.of(
                        "ai", new LLMException("AI analyst output missing required field: confidence")))
                .isEqualTo("response_schema_missing_field");
        assertThat(DispatchFailureStage.of("ai", new LLMException("AI analyst returned unknown confidence: WUT")))
                .isEqualTo("response_invalid_confidence");
        assertThat(DispatchFailureStage.of("ai", new LLMException("AI analyst output does not contain a JSON object")))
                .isEqualTo("response_not_object");
        assertThat(DispatchFailureStage.of("ai", new LLMException("empty response from AI analyst")))
                .isEqualTo("response_empty");
        assertThat(DispatchFailureStage.of("ai", new LLMException("something else entirely")))
                .isEqualTo("llm_other");
    }

    @Test
    void ai_LLMException_with_cause_classified_by_cause_simple_name() {
        assertThat(DispatchFailureStage.of(
                        "ai", new LLMException("Bedrock invocation failed", new AccessDeniedException())))
                .isEqualTo("bedrock_access_denied");
        assertThat(DispatchFailureStage.of(
                        "ai", new LLMException("Bedrock invocation failed", new ThrottlingException())))
                .isEqualTo("bedrock_throttling");
        assertThat(DispatchFailureStage.of(
                        "ai", new LLMException("Bedrock invocation failed", new ValidationException())))
                .isEqualTo("bedrock_validation");
        assertThat(DispatchFailureStage.of(
                        "ai", new LLMException("Bedrock invocation failed", new ResourceNotFoundException())))
                .isEqualTo("bedrock_resource_not_found");
        assertThat(DispatchFailureStage.of(
                        "ai", new LLMException("Bedrock invocation failed", new ServiceUnavailableException())))
                .isEqualTo("bedrock_unavailable");
        assertThat(DispatchFailureStage.of(
                        "ai", new LLMException("Bedrock invocation failed", new SdkClientException())))
                .isEqualTo("sdk_client_error");
        assertThat(DispatchFailureStage.of(
                        "ai", new LLMException("Bedrock invocation failed", new RuntimeException("???"))))
                .isEqualTo("bedrock_invoke:RuntimeException");
    }

    @Test
    void ai_non_LLMException_yields_llm_unknown() {
        assertThat(DispatchFailureStage.of("ai", new RuntimeException("rogue"))).isEqualTo("llm_unknown");
    }

    @Test
    void chart_classifies_render_exceptions_specifically() {
        assertThat(DispatchFailureStage.of("chart", new ChartRenderException(new RuntimeException("oom"))))
                .isEqualTo("chart_render");
        assertThat(DispatchFailureStage.of("chart", new RuntimeException("other")))
                .isEqualTo("chart_unknown");
    }

    @Test
    void email_component_yields_email_send() {
        assertThat(DispatchFailureStage.of("email", new RuntimeException("x"))).isEqualTo("email_send");
    }

    @Test
    void unknown_component_yields_unknown() {
        assertThat(DispatchFailureStage.of("nope", new RuntimeException("x"))).isEqualTo("unknown");
    }

    // Local test-doubles for AWS SDK exceptions — using fake names so we exercise
    // the simple-name switch without dragging in the actual Bedrock SDK types.
    private static final class AccessDeniedException extends RuntimeException {}

    private static final class ThrottlingException extends RuntimeException {}

    private static final class ValidationException extends RuntimeException {}

    private static final class ResourceNotFoundException extends RuntimeException {}

    private static final class ServiceUnavailableException extends RuntimeException {}

    private static final class SdkClientException extends RuntimeException {}
}
