package com.codepilot1c.core.provider.config;

import java.util.Locale;

import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.provider.ProviderUtils;
import com.google.gson.JsonObject;

/**
 * Resolves model-specific execution quirks for OpenAI-compatible endpoints.
 */
final class OpenAiModelCompatibilityPolicy {

    private static final int LARGE_TOOL_RESULT_CHARS = 50_000;
    private static final int LARGE_REQUEST_ESTIMATE_CHARS = 120_000;

    ProviderExecutionPlan plan(LlmProviderConfig config, LlmRequest request, boolean requestedStreaming) {
        boolean streaming = requestedStreaming && config.isStreamingEnabled();
        JsonObject overrides = new JsonObject();
        String model = resolveModelName(config, request).toLowerCase(Locale.ROOT);

        if (ProviderUtils.supportsBackendOptimizations(config)) {
            overrides.addProperty("parallel_tool_calls", false); //$NON-NLS-1$

            // Qwen-specific execution plan adjustments
            if (model.startsWith("qwen")) { //$NON-NLS-1$
                overrides.addProperty("temperature", 0.3); //$NON-NLS-1$
                if (request.hasTools()) {
                    overrides.addProperty("enable_thinking", false); //$NON-NLS-1$
                    if (hasLargeToolResult(request) || estimateRequestChars(request) > LARGE_REQUEST_ESTIMATE_CHARS) {
                        return ProviderExecutionPlan.of(false, overrides,
                                "Qwen backend: large tool result -> non-stream for stability"); //$NON-NLS-1$
                    }
                    return ProviderExecutionPlan.of(streaming, overrides,
                            "Qwen backend: temperature=0.3, enable_thinking=false, parallel_tool_calls=false"); //$NON-NLS-1$
                }
                return ProviderExecutionPlan.of(streaming, overrides,
                        "Qwen backend: temperature=0.3"); //$NON-NLS-1$
            }

            return ProviderExecutionPlan.of(streaming, overrides,
                    "codepilot backend uses explicit backend execution plan"); //$NON-NLS-1$
        }

        if (request.hasTools()) {
            if (model.contains("glm-5")) { //$NON-NLS-1$
                return ProviderExecutionPlan.of(false, overrides,
                        "glm-5 uses reasoning-first tool streaming; prefer non-stream for tool calls"); //$NON-NLS-1$
            }
            if (model.contains("minimax-m2.5")) { //$NON-NLS-1$
                return ProviderExecutionPlan.of(false, overrides,
                        "MiniMax-M2.5 tool calls are more stable in non-stream mode"); //$NON-NLS-1$
            }
            if (model.contains("kimi-k2.5")) { //$NON-NLS-1$
                overrides.addProperty("enable_thinking", false); //$NON-NLS-1$
                if (hasLargeToolResult(request) || estimateRequestChars(request) > LARGE_REQUEST_ESTIMATE_CHARS) {
                    return ProviderExecutionPlan.of(false, overrides,
                            "kimi-k2.5 large tool-result follow-up uses non-stream mode to avoid stream timeout"); //$NON-NLS-1$
                }
                return ProviderExecutionPlan.of(streaming, overrides,
                        "kimi-k2.5 tool requests use enable_thinking=false for generic provider compatibility"); //$NON-NLS-1$
            }
        }

        return ProviderExecutionPlan.of(streaming, overrides, null);
    }

    private String resolveModelName(LlmProviderConfig config, LlmRequest request) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return config.getModel() != null ? config.getModel() : ""; //$NON-NLS-1$
    }

    private boolean hasLargeToolResult(LlmRequest request) {
        return request.getMessages().stream()
                .filter(message -> message != null && message.isToolResult())
                .anyMatch(message -> message.getContent() != null && message.getContent().length() > LARGE_TOOL_RESULT_CHARS);
    }

    private int estimateRequestChars(LlmRequest request) {
        return request.getMessages().stream()
                .filter(message -> message != null && message.getContent() != null)
                .mapToInt(message -> message.getContent().length())
                .sum();
    }
}
