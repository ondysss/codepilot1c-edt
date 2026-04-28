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

            // Kimi/Moonshot models via CodePilot backend:
            // Per qwen-code reference implementation, kimi-k2.5 works best with:
            //   - thinking ENABLED (not disabled) — model uses reasoning_content naturally
            //   - streaming ENABLED — structured tool_calls arrive via SSE deltas
            //   - reasoning_content preserved in conversation history (handled in DynamicLlmProvider)
            // The previous approach (thinking=disabled + non-stream) caused reasoning-only
            // responses because reasoning_content was not being preserved in history.
            if (model.startsWith("kimi") || model.startsWith("moonshot")) { //$NON-NLS-1$ //$NON-NLS-2$
                overrides.addProperty("temperature", 0.6); //$NON-NLS-1$
                if (request.hasTools()) {
                    if (hasLargeToolResult(request) || estimateRequestChars(request) > LARGE_REQUEST_ESTIMATE_CHARS) {
                        return ProviderExecutionPlan.of(false, overrides,
                                "kimi backend: large tool result -> non-stream for stability"); //$NON-NLS-1$
                    }
                    return ProviderExecutionPlan.of(streaming, overrides,
                            "kimi backend: thinking enabled, streaming, reasoning_content preserved"); //$NON-NLS-1$
                }
                return ProviderExecutionPlan.of(streaming, overrides,
                        "kimi backend: temperature=0.6"); //$NON-NLS-1$
            }

            // DeepSeek thinking models via LiteLLM require the exact assistant
            // reasoning_content to be replayed after tool calls. Workspace logs
            // (2026-04-25, deepseek-v4-flash) show streaming tool-call responses can
            // complete with reasoningChunks=0 even though the next request is still
            // validated as thinking-mode history. Non-stream responses include the
            // full reasoning_content field and can be safely replayed.
            if (model.startsWith("deepseek") && request.hasTools()) { //$NON-NLS-1$
                return ProviderExecutionPlan.of(false, overrides,
                        "DeepSeek backend: tool calls use non-stream mode to preserve reasoning_content"); //$NON-NLS-1$
            }

            // MiniMax M2 family via CodePilot backend:
            // Tool call IDs are only stable in non-stream responses. In streaming mode the
            // aggregated tool_call_id may diverge from what MiniMax expects on the follow-up
            // request, yielding "tool result's tool id(...) not found (2013)".
            if (isMiniMaxM2(model)) {
                if (request.hasTools()) {
                    return ProviderExecutionPlan.of(false, overrides,
                            "MiniMax M2 backend: tool calls use non-stream mode for stable tool_call_id"); //$NON-NLS-1$
                }
                return ProviderExecutionPlan.of(streaming, overrides,
                        "MiniMax M2 backend: streaming allowed without tools"); //$NON-NLS-1$
            }

            // CodePilot backend "auto" routing with tools:
            // Workspace logs (2026-04-23) show req-6 hang 120s with zero SSE chunks when the
            // client sends model="auto" + 58 tools. The backend resolves "auto" → kimi-k2.5 or
            // minimax-m2.7 server-side; both variants reasoning-think for a long time before the
            // first delta, and the client read-timeout fires before any byte arrives.
            // Non-stream replay avoids the hang entirely: billing data from the same sessions
            // confirms prompt cache works correctly non-stream (92–99% cache hit on
            // minimax-m2.7), so we only trade SSE liveness — which is already zero in practice —
            // for reliability. Downstream replay path preserves tool_calls + reasoning_content.
            if (model.equals("auto") && request.hasTools()) { //$NON-NLS-1$
                if (hasLargeToolResult(request) || estimateRequestChars(request) > LARGE_REQUEST_ESTIMATE_CHARS) {
                    return ProviderExecutionPlan.of(false, overrides,
                            "codepilot backend: auto routing with large tool result -> non-stream"); //$NON-NLS-1$
                }
                return ProviderExecutionPlan.of(false, overrides,
                        "codepilot backend: auto routing with tools -> non-stream (SSE timeouts observed on kimi-k2.5)"); //$NON-NLS-1$
            }

            // Other CodePilot backend models (explicit but unknown family):
            // Use conservative settings — keep streaming enabled but don't force thinking disabled.
            if (request.hasTools()) {
                if (hasLargeToolResult(request) || estimateRequestChars(request) > LARGE_REQUEST_ESTIMATE_CHARS) {
                    return ProviderExecutionPlan.of(false, overrides,
                            "codepilot backend: large tool result -> non-stream for stability"); //$NON-NLS-1$
                }
                return ProviderExecutionPlan.of(streaming, overrides,
                        "codepilot backend: streaming with parallel_tool_calls=false"); //$NON-NLS-1$
            }

            return ProviderExecutionPlan.of(streaming, overrides,
                    "codepilot backend uses explicit backend execution plan"); //$NON-NLS-1$
        }

        if (request.hasTools()) {
            if (model.contains("glm-5")) { //$NON-NLS-1$
                return ProviderExecutionPlan.of(false, overrides,
                        "glm-5 uses reasoning-first tool streaming; prefer non-stream for tool calls"); //$NON-NLS-1$
            }
            if (isMiniMaxM2(model)) {
                return ProviderExecutionPlan.of(false, overrides,
                        "MiniMax M2 tool calls are more stable in non-stream mode"); //$NON-NLS-1$
            }
            if (model.contains("kimi-k2.5") || model.contains("kimi-k2")) { //$NON-NLS-1$ //$NON-NLS-2$
                // Moonshot API uses {"thinking":{"type":"disabled"}}. Keep the legacy
                // enable_thinking=false hint for OpenAI-compatible gateways that honor it.
                // Without this, thinking stays enabled by default and the model produces reasoning-only
                // responses (contentChunks=0) after tool calls, consuming the entire token budget on reasoning.
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "disabled"); //$NON-NLS-1$ //$NON-NLS-2$
                overrides.add("thinking", thinking); //$NON-NLS-1$
                overrides.addProperty("enable_thinking", false); //$NON-NLS-1$
                overrides.addProperty("temperature", 0.6); //$NON-NLS-1$
                if (hasLargeToolResult(request) || estimateRequestChars(request) > LARGE_REQUEST_ESTIMATE_CHARS) {
                    return ProviderExecutionPlan.of(false, overrides,
                            "kimi-k2.5 large tool-result follow-up uses non-stream mode to avoid stream timeout"); //$NON-NLS-1$
                }
                return ProviderExecutionPlan.of(streaming, overrides,
                        "kimi-k2.5 tool requests use thinking.type=disabled per Moonshot API spec"); //$NON-NLS-1$
            }
        }

        return ProviderExecutionPlan.of(streaming, overrides, null);
    }

    private static final String MINIMAX_M2_TOKEN = "minimax-m2"; //$NON-NLS-1$

    /**
     * MiniMax M2 family detection (m2.5, m2.7, future m2.x). Model name is already lowercased.
     * <p>
     * Uses token-boundary matching to avoid false positives like {@code notminimax-m2} or
     * {@code minimax-m20}. Narrower than {@code contains("minimax")} to avoid pre-emptively
     * capturing hypothetical future families (m3.x, etc.) without evidence of the same
     * streaming tool_call_id quirk.
     */
    private static boolean isMiniMaxM2(String model) {
        if (model == null) {
            return false;
        }
        int idx = model.indexOf(MINIMAX_M2_TOKEN);
        if (idx < 0) {
            return false;
        }
        if (idx > 0 && Character.isLetterOrDigit(model.charAt(idx - 1))) {
            return false;
        }
        int end = idx + MINIMAX_M2_TOKEN.length();
        if (end == model.length()) {
            return true;
        }
        char next = model.charAt(end);
        if (next == '.') {
            return true;
        }
        return !Character.isLetterOrDigit(next);
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
