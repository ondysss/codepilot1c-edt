/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.claude;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.AbstractLlmProvider;
import com.codepilot1c.core.provider.LlmProviderException;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * LLM provider implementation for Anthropic Claude API.
 */
public class ClaudeProvider extends AbstractLlmProvider {

    private static final String API_VERSION = "2023-06-01"; //$NON-NLS-1$
    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ClaudeProvider.class);

    // Accumulate tool calls during streaming
    private final List<StreamingToolCall> streamingToolCalls = new ArrayList<>();
    private boolean streamCompletionSent = false;

    @Override
    public String getId() {
        return "claude"; //$NON-NLS-1$
    }

    @Override
    public String getDisplayName() {
        return "Claude (Anthropic)"; //$NON-NLS-1$
    }

    @Override
    public boolean isConfigured() {
        String apiKey = getApiKey();
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    private String getApiKey() {
        return getPreferences().get(VibePreferenceConstants.PREF_CLAUDE_API_KEY, ""); //$NON-NLS-1$
    }

    private String getApiUrl() {
        return getPreferences().get(VibePreferenceConstants.PREF_CLAUDE_API_URL,
                "https://api.anthropic.com/v1"); //$NON-NLS-1$
    }

    private String getModel() {
        String model = getPreferences().get(VibePreferenceConstants.PREF_CLAUDE_MODEL, ""); //$NON-NLS-1$
        // Return fallback if preference is empty (not configured)
        return model.isEmpty() ? "claude-sonnet-4-20250514" : model; //$NON-NLS-1$
    }

    private int getMaxTokens() {
        return getPreferences().getInt(VibePreferenceConstants.PREF_CLAUDE_MAX_TOKENS, 4096);
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        resetCancelled();
        long startTime = System.currentTimeMillis();
        String correlationId = LogSanitizer.newCorrelationId();

        if (!isConfigured()) {
            LOG.warn("[%s] Claude API key is not configured", correlationId); //$NON-NLS-1$
            return CompletableFuture.failedFuture(
                    new LlmProviderException("Claude API key is not configured")); //$NON-NLS-1$
        }

        LOG.info("[%s] Claude complete: model=%s, messages=%d", //$NON-NLS-1$
                correlationId, getModel(), request.getMessages().size());

        String requestBody = buildRequestBody(request, false);
        HttpRequest httpRequest = createPostRequest(getApiUrl() + "/messages") //$NON-NLS-1$
                .header("x-api-key", getApiKey()) //$NON-NLS-1$
                .header("anthropic-version", API_VERSION) //$NON-NLS-1$
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        return sendAsync(httpRequest)
                .thenApply(response -> {
                    LlmResponse llmResponse = parseResponse(response);
                    long duration = System.currentTimeMillis() - startTime;
                    LOG.info("[%s] Claude response received in %s, tokens: %d/%d", //$NON-NLS-1$
                            correlationId, LogSanitizer.formatDuration(duration),
                            llmResponse.getUsage() != null ? llmResponse.getUsage().getPromptTokens() : 0,
                            llmResponse.getUsage() != null ? llmResponse.getUsage().getCompletionTokens() : 0);
                    return llmResponse;
                })
                .whenComplete((result, error) -> {
                    if (error != null) {
                        LOG.error("[%s] Claude request failed after %s: %s", //$NON-NLS-1$
                                correlationId, LogSanitizer.formatDuration(System.currentTimeMillis() - startTime),
                                error.getMessage());
                    }
                });
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
        resetCancelled();
        long startTime = System.currentTimeMillis();
        String correlationId = LogSanitizer.newCorrelationId();

        if (!isConfigured()) {
            LOG.warn("[%s] Claude API key is not configured (stream)", correlationId); //$NON-NLS-1$
            throw new LlmProviderException("Claude API key is not configured"); //$NON-NLS-1$
        }

        LOG.info("[%s] Claude streamComplete: model=%s, messages=%d", //$NON-NLS-1$
                correlationId, getModel(), request.getMessages().size());

        String requestBody = buildRequestBody(request, true);
        HttpRequest httpRequest = createPostRequest(getApiUrl() + "/messages") //$NON-NLS-1$
                .header("x-api-key", getApiKey()) //$NON-NLS-1$
                .header("anthropic-version", API_VERSION) //$NON-NLS-1$
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        final LlmProviderException[] error = { null };

        sendAsyncStreaming(
                httpRequest,
                (line, complete) -> processStreamLine(line, consumer, complete),
                ex -> {
                    if (ex instanceof LlmProviderException) {
                        error[0] = (LlmProviderException) ex;
                    } else {
                        error[0] = new LlmProviderException("Failed to stream from Claude API", ex); //$NON-NLS-1$
                    }
                    LOG.error("[%s] Claude stream error: %s", correlationId, error[0].getMessage()); //$NON-NLS-1$
                    consumer.accept(LlmStreamChunk.error(error[0].getMessage()));
                }
        ).join(); // Block here to maintain method contract, but streaming happens async

        long duration = System.currentTimeMillis() - startTime;
        if (error[0] != null) {
            LOG.error("[%s] Claude stream failed after %s", correlationId, LogSanitizer.formatDuration(duration)); //$NON-NLS-1$
            throw error[0];
        }
        LOG.info("[%s] Claude stream completed in %s", correlationId, LogSanitizer.formatDuration(duration)); //$NON-NLS-1$
    }

    private void processStreamLine(String line, Consumer<LlmStreamChunk> consumer, Runnable complete) {
        if (!line.startsWith("data: ")) { //$NON-NLS-1$
            return;
        }

        String data = line.substring(6);
        if ("[DONE]".equals(data)) { //$NON-NLS-1$
            consumer.accept(LlmStreamChunk.complete("stop")); //$NON-NLS-1$
            complete.run();
            return;
        }

        try {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            String type = event.get("type").getAsString(); //$NON-NLS-1$

            if ("content_block_start".equals(type)) { //$NON-NLS-1$
                // Tool use blocks are announced with content_block_start.
                // Example: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"...","name":"grep","input":{}}}
                if (!event.has("index") || !event.has("content_block")) { //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                int index = event.get("index").getAsInt(); //$NON-NLS-1$
                JsonObject contentBlock = event.getAsJsonObject("content_block"); //$NON-NLS-1$
                if (!contentBlock.has("type")) { //$NON-NLS-1$
                    return;
                }

                String blockType = contentBlock.get("type").getAsString(); //$NON-NLS-1$
                if (!"tool_use".equals(blockType)) { //$NON-NLS-1$
                    return;
                }

                ensureStreamingToolCallSlot(index);
                StreamingToolCall tc = streamingToolCalls.get(index);

                if (contentBlock.has("id")) { //$NON-NLS-1$
                    tc.id = contentBlock.get("id").getAsString(); //$NON-NLS-1$
                }
                if (contentBlock.has("name")) { //$NON-NLS-1$
                    tc.name = contentBlock.get("name").getAsString(); //$NON-NLS-1$
                }

                // Some servers send full input object immediately; otherwise we'll receive deltas.
                if (contentBlock.has("input") && !contentBlock.get("input").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                    String inputJson = contentBlock.get("input").toString(); //$NON-NLS-1$
                    if (inputJson != null && !inputJson.isBlank() && !"{}".equals(inputJson)) { //$NON-NLS-1$
                        tc.input.setLength(0);
                        tc.input.append(inputJson);
                    }
                }
            } else if ("content_block_delta".equals(type)) { //$NON-NLS-1$
                if (!event.has("index") || !event.has("delta")) { //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                int index = event.get("index").getAsInt(); //$NON-NLS-1$
                JsonObject delta = event.getAsJsonObject("delta"); //$NON-NLS-1$

                // Text streaming
                if (delta.has("text")) { //$NON-NLS-1$
                    consumer.accept(LlmStreamChunk.content(delta.get("text").getAsString())); //$NON-NLS-1$
                    return;
                }

                // Tool input streaming (partial JSON)
                // Example: {"type":"input_json_delta","partial_json":"{\"query\":\"...\""}
                if (delta.has("type") && "input_json_delta".equals(delta.get("type").getAsString()) //$NON-NLS-1$ //$NON-NLS-2$
                        && delta.has("partial_json")) { //$NON-NLS-1$
                    ensureStreamingToolCallSlot(index);
                    StreamingToolCall tc = streamingToolCalls.get(index);
                    tc.input.append(delta.get("partial_json").getAsString()); //$NON-NLS-1$
                }
            } else if ("message_stop".equals(type)) { //$NON-NLS-1$
                // If tools were requested during streaming, emit them before completion.
                if (!streamCompletionSent) {
                    List<ToolCall> toolCalls = finalizeStreamingToolCalls();
                    if (!toolCalls.isEmpty()) {
                        streamCompletionSent = true;
                        consumer.accept(LlmStreamChunk.toolCalls(toolCalls));
                        consumer.accept(LlmStreamChunk.complete(LlmResponse.FINISH_REASON_TOOL_USE));
                        complete.run();
                        return;
                    }
                }

                consumer.accept(LlmStreamChunk.complete("stop")); //$NON-NLS-1$
                complete.run();
            }
        } catch (Exception e) {
            // Skip malformed lines
        }
    }

    private void ensureStreamingToolCallSlot(int index) {
        while (streamingToolCalls.size() <= index) {
            streamingToolCalls.add(new StreamingToolCall());
        }
    }

    private List<ToolCall> finalizeStreamingToolCalls() {
        List<ToolCall> result = new ArrayList<>();
        for (StreamingToolCall stc : streamingToolCalls) {
            if (stc != null && stc.id != null && stc.name != null) {
                result.add(new ToolCall(stc.id, stc.name, stc.input.toString()));
            }
        }
        return result;
    }

    private String buildRequestBody(LlmRequest request, boolean stream) {
        JsonObject body = new JsonObject();

        String model = request.getModel() != null ? request.getModel() : getModel();
        body.addProperty("model", model); //$NON-NLS-1$
        body.addProperty("max_tokens", //$NON-NLS-1$
                request.getMaxTokens() > 0 ? request.getMaxTokens() : getMaxTokens());

        if (stream) {
            body.addProperty("stream", true); //$NON-NLS-1$
        }

        // Extract system message and build messages array
        JsonArray messages = new JsonArray();
        for (LlmMessage msg : request.getMessages()) {
            if (msg.getRole() == LlmMessage.Role.SYSTEM) {
                body.addProperty("system", msg.getContent()); //$NON-NLS-1$
            } else {
                messages.add(serializeMessage(msg));
            }
        }
        body.add("messages", messages); //$NON-NLS-1$

        // Add tools if present
        if (request.hasTools()) {
            JsonArray tools = new JsonArray();
            for (ToolDefinition tool : request.getTools()) {
                tools.add(serializeToolDefinition(tool));
            }
            body.add("tools", tools); //$NON-NLS-1$
            LOG.debug("Added %d tools to request", request.getTools().size()); //$NON-NLS-1$
        }

        String json = gson.toJson(body);
        LOG.debug("Request body: %s", json); //$NON-NLS-1$
        return json;
    }

    private JsonObject serializeMessage(LlmMessage msg) {
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("role", msg.getRole().getValue()); //$NON-NLS-1$

        if (msg.getRole() == LlmMessage.Role.TOOL) {
            // Tool result - Claude uses content array with tool_result type
            JsonArray contentArray = new JsonArray();
            JsonObject toolResult = new JsonObject();
            toolResult.addProperty("type", "tool_result"); //$NON-NLS-1$ //$NON-NLS-2$
            toolResult.addProperty("tool_use_id", msg.getToolCallId()); //$NON-NLS-1$
            toolResult.addProperty("content", msg.getContent()); //$NON-NLS-1$
            contentArray.add(toolResult);
            msgObj.add("content", contentArray); //$NON-NLS-1$
            // Claude expects tool results with role "user"
            msgObj.addProperty("role", "user"); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (msg.hasToolCalls()) {
            // Assistant message with tool calls
            JsonArray contentArray = new JsonArray();

            // Add text content if present
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                JsonObject textBlock = new JsonObject();
                textBlock.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
                textBlock.addProperty("text", msg.getContent()); //$NON-NLS-1$
                contentArray.add(textBlock);
            }

            // Add tool use blocks
            for (ToolCall call : msg.getToolCalls()) {
                JsonObject toolUse = new JsonObject();
                toolUse.addProperty("type", "tool_use"); //$NON-NLS-1$ //$NON-NLS-2$
                toolUse.addProperty("id", call.getId()); //$NON-NLS-1$
                toolUse.addProperty("name", call.getName()); //$NON-NLS-1$

                // Parse arguments as JSON
                try {
                    JsonElement input = JsonParser.parseString(call.getArguments());
                    toolUse.add("input", input); //$NON-NLS-1$
                } catch (Exception e) {
                    toolUse.add("input", new JsonObject()); //$NON-NLS-1$
                }
                contentArray.add(toolUse);
            }
            msgObj.add("content", contentArray); //$NON-NLS-1$
        } else {
            // Regular message
            msgObj.addProperty("content", msg.getContent()); //$NON-NLS-1$
        }

        return msgObj;
    }

    private JsonObject serializeToolDefinition(ToolDefinition tool) {
        JsonObject toolObj = new JsonObject();
        toolObj.addProperty("name", tool.getName()); //$NON-NLS-1$
        toolObj.addProperty("description", tool.getDescription()); //$NON-NLS-1$

        // Parse the parameters schema JSON
        try {
            JsonElement params = JsonParser.parseString(tool.getParametersSchema());
            toolObj.add("input_schema", params); //$NON-NLS-1$
        } catch (Exception e) {
            // Fallback to empty object with type
            JsonObject emptySchema = new JsonObject();
            emptySchema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
            toolObj.add("input_schema", emptySchema); //$NON-NLS-1$
        }

        return toolObj;
    }

    private LlmResponse parseResponse(HttpResponse<String> response) {
        if (!isSuccess(response.statusCode())) {
            throw parseError(response);
        }

        LOG.debug("Response body: %s", response.body()); //$NON-NLS-1$

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            StringBuilder content = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();

            JsonArray contentArray = json.getAsJsonArray("content"); //$NON-NLS-1$
            for (JsonElement element : contentArray) {
                JsonObject block = element.getAsJsonObject();
                String type = block.get("type").getAsString(); //$NON-NLS-1$

                if ("text".equals(type)) { //$NON-NLS-1$
                    content.append(block.get("text").getAsString()); //$NON-NLS-1$
                } else if ("tool_use".equals(type)) { //$NON-NLS-1$
                    String id = block.get("id").getAsString(); //$NON-NLS-1$
                    String name = block.get("name").getAsString(); //$NON-NLS-1$
                    String input = block.get("input").toString(); // Keep as JSON string //$NON-NLS-1$

                    toolCalls.add(new ToolCall(id, name, input));
                    LOG.debug("Parsed tool_use: %s(%s)", name, input); //$NON-NLS-1$
                }
            }

            String model = json.has("model") ? json.get("model").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
            String stopReason = json.has("stop_reason") ? json.get("stop_reason").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$

            // Claude uses "tool_use" as stop_reason when tools are called
            if ("tool_use".equals(stopReason) || !toolCalls.isEmpty()) { //$NON-NLS-1$
                stopReason = LlmResponse.FINISH_REASON_TOOL_USE;
            }

            LlmResponse.Usage usage = null;
            if (json.has("usage")) { //$NON-NLS-1$
                JsonObject usageJson = json.getAsJsonObject("usage"); //$NON-NLS-1$
                int inputTokens = usageJson.get("input_tokens").getAsInt(); //$NON-NLS-1$
                int outputTokens = usageJson.get("output_tokens").getAsInt(); //$NON-NLS-1$
                usage = new LlmResponse.Usage(inputTokens, outputTokens, inputTokens + outputTokens);
            }

            return new LlmResponse(content.toString(), model, usage, stopReason,
                    toolCalls.isEmpty() ? null : toolCalls);
        } catch (Exception e) {
            LOG.error("Failed to parse Claude response", e); //$NON-NLS-1$
            throw new LlmProviderException("Failed to parse Claude response", e); //$NON-NLS-1$
        }
    }

    /**
     * Helper class to accumulate streamed tool call data.
     */
    private static class StreamingToolCall {
        String id;
        String name;
        StringBuilder input = new StringBuilder();
    }

    private LlmProviderException parseError(HttpResponse<String> response) {
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("error")) { //$NON-NLS-1$
                JsonObject error = json.getAsJsonObject("error"); //$NON-NLS-1$
                String type = error.has("type") ? error.get("type").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
                String message = error.has("message") ? error.get("message").getAsString() //$NON-NLS-1$ //$NON-NLS-2$
                        : "Unknown error"; //$NON-NLS-1$
                return new LlmProviderException(message, null, response.statusCode(), type);
            }
        } catch (Exception ignored) {
            // Fall through to default error
        }
        return new LlmProviderException(
                "Claude API error: " + response.statusCode(), null, response.statusCode(), null); //$NON-NLS-1$
    }
}
