package com.heikinashi.monitoring.application;

import com.heikinashi.monitoring.domain.error.ChartRenderException;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.LLMException;

/**
 * Maps a dispatch-time failure into a fine-grained sub-stage identifier used
 * only in diagnostic log lines. Does not affect retry semantics or persisted
 * error codes — those keep the broad {@code LLM_ERROR} / {@code CHART_RENDER_FAILED}
 * codes the spec defines.
 */
final class DispatchFailureStage {

    private DispatchFailureStage() {}

    static String of(String component, RuntimeException e) {
        if (e instanceof DependencyUnavailableException) {
            return "dependency_unavailable";
        }
        return switch (component) {
            case "ai" -> aiStage(e);
            case "chart" -> chartStage(e);
            case "email" -> "email_send";
            default -> "unknown";
        };
    }

    private static String aiStage(RuntimeException e) {
        if (!(e instanceof LLMException)) {
            return "llm_unknown";
        }
        Throwable cause = e.getCause();
        if (cause == null) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("MAX_TOKENS")) return "max_tokens";
            if (msg.contains("Unexpected Bedrock stop reason")) return "unexpected_stop";
            if (msg.contains("non-JSON")) return "response_non_json";
            if (msg.contains("missing required field")) return "response_schema_missing_field";
            if (msg.contains("unknown confidence")) return "response_invalid_confidence";
            if (msg.contains("does not contain a JSON object")) return "response_not_object";
            if (msg.contains("empty response")) return "response_empty";
            return "llm_other";
        }
        String causeClass = cause.getClass().getSimpleName();
        return switch (causeClass) {
            case "AccessDeniedException" -> "bedrock_access_denied";
            case "ThrottlingException" -> "bedrock_throttling";
            case "ValidationException" -> "bedrock_validation";
            case "ModelNotReadyException" -> "bedrock_model_not_ready";
            case "ResourceNotFoundException" -> "bedrock_resource_not_found";
            case "ServiceUnavailableException" -> "bedrock_unavailable";
            case "InternalServerException" -> "bedrock_internal_server";
            case "BedrockRuntimeException" -> "bedrock_runtime_other";
            case "SdkClientException" -> "sdk_client_error";
            default -> "bedrock_invoke:" + causeClass;
        };
    }

    private static String chartStage(RuntimeException e) {
        if (e instanceof ChartRenderException) {
            return "chart_render";
        }
        return "chart_unknown";
    }
}
