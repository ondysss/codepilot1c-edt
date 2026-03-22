/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.util.List;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmConversationSanitizer;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Separate transport layer for Qwen models via CodePilot backend.
 *
 * <p>This transport implements the <b>dual-mode</b> approach used by Qwen Code
 * (official CLI by Alibaba):</p>
 * <ol>
 *   <li><b>Structured API</b>: tools are sent via the standard OpenAI {@code tools}
 *       parameter &mdash; this is the primary channel for tool calls</li>
 *   <li><b>XML priming</b>: tool call examples are injected into the system message
 *       to help the model understand the tool calling workflow. Qwen models were
 *       trained on this format and it measurably improves tool call accuracy.</li>
 * </ol>
 *
 * <p>This class is activated <b>only</b> when the active provider is CodePilot backend
 * ({@code ProviderCapabilities.isQwenNative() == true}). It does not modify the
 * existing OpenAI-compatible request building path.</p>
 *
 * <p>The response parsing side is handled by {@link QwenContentToolCallParser}
 * (content fallback) and {@link QwenStreamingToolCallParser} (streaming repair).</p>
 */
final class QwenFunctionCallingTransport {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QwenFunctionCallingTransport.class);

    private final Gson gson;

    QwenFunctionCallingTransport(Gson gson) {
        this.gson = gson;
    }

    /**
     * Builds a request body for Qwen models with dual-mode tool calling.
     *
     * @param request       the LLM request
     * @param executionPlan the resolved execution plan (streaming, overrides)
     * @param config        the provider configuration
     * @param caps          the provider capabilities (must have {@code isQwenNative() == true})
     * @return JSON request body string
     */
    String buildRequestBody(LlmRequest request, ProviderExecutionPlan executionPlan,
            LlmProviderConfig config, ProviderCapabilities caps) {
        JsonObject body = new JsonObject();

        // Model
        body.addProperty("model", resolveModelName(config, request)); //$NON-NLS-1$

        // Max tokens
        int maxTokens = request.getMaxTokens() > 0 ? request.getMaxTokens() : config.getMaxTokens();
        body.addProperty("max_tokens", maxTokens); //$NON-NLS-1$

        // Temperature: use Qwen default if request doesn't override
        float temperature = caps.getDefaultTemperature();
        if (temperature > 0) {
            body.addProperty("temperature", temperature); //$NON-NLS-1$
        }

        // Streaming
        body.addProperty("stream", executionPlan.isStreaming()); //$NON-NLS-1$

        // Messages with XML tool call priming in system message
        JsonArray messages = buildMessagesWithToolCallPriming(request, caps);
        body.add("messages", messages); //$NON-NLS-1$

        // Tools via structured API (primary channel)
        if (request.hasTools()) {
            JsonArray tools = new JsonArray();
            for (ToolDefinition tool : request.getTools()) {
                tools.add(serializeToolDefinition(tool));
            }
            body.add("tools", tools); //$NON-NLS-1$
            LOG.debug("Qwen transport: added %d tools to request", request.getTools().size()); //$NON-NLS-1$

            if (request.getToolChoice() != null) {
                body.addProperty("tool_choice", serializeToolChoice(request.getToolChoice())); //$NON-NLS-1$
            }
        }

        // Qwen-specific overrides
        applyQwenOverrides(body, caps, request);

        // Execution plan overrides
        executionPlan.getRequestOverrides().entrySet()
                .forEach(entry -> body.add(entry.getKey(), entry.getValue().deepCopy()));

        String result = gson.toJson(body);
        LOG.debug("Qwen transport: built request body (%d chars), family=%s", //$NON-NLS-1$
                result.length(), caps.getResolvedModelFamily());
        return result;
    }

    /**
     * Builds messages array with XML tool call examples injected into the system message.
     * The format is selected based on the resolved model family.
     */
    private JsonArray buildMessagesWithToolCallPriming(LlmRequest request, ProviderCapabilities caps) {
        JsonArray messages = new JsonArray();
        List<LlmMessage> sanitizedMessages = LlmConversationSanitizer
                .sanitizeForOpenAiToolCalls(request.getMessages());

        for (LlmMessage msg : sanitizedMessages) {
            if (msg.getRole() == LlmMessage.Role.SYSTEM && request.hasTools()) {
                // Inject tool call examples AFTER the system prompt text
                String toolCallExamples = QwenToolCallExamples.getExamples(caps, request.getTools());
                if (!toolCallExamples.isEmpty()) {
                    String augmented = msg.getContent() + "\n\n" + toolCallExamples; //$NON-NLS-1$
                    JsonObject augMsg = serializeMessage(msg);
                    augMsg.addProperty("content", augmented); //$NON-NLS-1$
                    messages.add(augMsg);
                    LOG.debug("Qwen transport: injected %d chars of tool call examples into system message", //$NON-NLS-1$
                            toolCallExamples.length());
                } else {
                    messages.add(serializeMessage(msg));
                }
            } else {
                messages.add(serializeMessage(msg));
            }
        }

        return messages;
    }

    /**
     * Applies Qwen-specific request body overrides.
     */
    private void applyQwenOverrides(JsonObject body, ProviderCapabilities caps, LlmRequest request) {
        // Disable thinking for tool call requests (matches kimi-k2.5 pattern).
        // Qwen's thinking mode can interfere with structured tool call output.
        if (request.hasTools()) {
            body.addProperty("enable_thinking", false); //$NON-NLS-1$
        }

        // Disable parallel tool calls for stability
        body.addProperty("parallel_tool_calls", false); //$NON-NLS-1$
    }

    private String resolveModelName(LlmProviderConfig config, LlmRequest request) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return config.getModel();
    }

    // ---- Serialization helpers (mirror DynamicLlmProvider's internal format) ----

    private JsonObject serializeMessage(LlmMessage msg) {
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("role", msg.getRole().getValue()); //$NON-NLS-1$

        if (msg.getRole() == LlmMessage.Role.TOOL) {
            msgObj.addProperty("tool_call_id", msg.getToolCallId()); //$NON-NLS-1$
            msgObj.addProperty("content", msg.getContent()); //$NON-NLS-1$
        } else if (msg.hasToolCalls()) {
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                msgObj.addProperty("content", msg.getContent()); //$NON-NLS-1$
            } else {
                msgObj.add("content", null); //$NON-NLS-1$
            }

            JsonArray toolCalls = new JsonArray();
            for (ToolCall call : msg.getToolCalls()) {
                JsonObject callObj = new JsonObject();
                callObj.addProperty("id", call.getId()); //$NON-NLS-1$
                callObj.addProperty("type", "function"); //$NON-NLS-1$ //$NON-NLS-2$

                JsonObject functionObj = new JsonObject();
                functionObj.addProperty("name", call.getName()); //$NON-NLS-1$
                functionObj.addProperty("arguments", call.getArguments()); //$NON-NLS-1$
                callObj.add("function", functionObj); //$NON-NLS-1$

                toolCalls.add(callObj);
            }
            msgObj.add("tool_calls", toolCalls); //$NON-NLS-1$
        } else {
            msgObj.addProperty("content", msg.getContent()); //$NON-NLS-1$
        }

        return msgObj;
    }

    private JsonObject serializeToolDefinition(ToolDefinition tool) {
        JsonObject toolObj = new JsonObject();
        toolObj.addProperty("type", "function"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject functionObj = new JsonObject();
        functionObj.addProperty("name", tool.getName()); //$NON-NLS-1$
        functionObj.addProperty("description", tool.getDescription()); //$NON-NLS-1$

        try {
            JsonElement params = JsonParser.parseString(tool.getParametersSchema());
            functionObj.add("parameters", params); //$NON-NLS-1$
        } catch (Exception e) {
            functionObj.add("parameters", new JsonObject()); //$NON-NLS-1$
        }

        toolObj.add("function", functionObj); //$NON-NLS-1$
        return toolObj;
    }

    private String serializeToolChoice(LlmRequest.ToolChoice choice) {
        switch (choice) {
            case AUTO:
                return "auto"; //$NON-NLS-1$
            case REQUIRED:
                return "required"; //$NON-NLS-1$
            case NONE:
                return "none"; //$NON-NLS-1$
            default:
                return "auto"; //$NON-NLS-1$
        }
    }
}
