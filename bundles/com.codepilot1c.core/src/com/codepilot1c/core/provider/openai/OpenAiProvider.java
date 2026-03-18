/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.openai;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmConversationSanitizer;
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
 * LLM provider implementation for OpenAI API.
 */
public class OpenAiProvider extends AbstractLlmProvider {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(OpenAiProvider.class);

    @Override
    public String getId() {
        return "openai"; //$NON-NLS-1$
    }

    @Override
    public String getDisplayName() {
        return "OpenAI GPT"; //$NON-NLS-1$
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
        return getPreferences().get(VibePreferenceConstants.PREF_OPENAI_API_KEY, ""); //$NON-NLS-1$
    }

    private String getApiUrl() {
        return getPreferences().get(VibePreferenceConstants.PREF_OPENAI_API_URL,
                "https://api.openai.com/v1"); //$NON-NLS-1$
    }

    private String getModel() {
        String model = getPreferences().get(VibePreferenceConstants.PREF_OPENAI_MODEL, ""); //$NON-NLS-1$
        // Return fallback if preference is empty (not configured)
        return model.isEmpty() ? "gpt-4o" : model; //$NON-NLS-1$
    }

    private int getMaxTokens() {
        return getPreferences().getInt(VibePreferenceConstants.PREF_OPENAI_MAX_TOKENS, 4096);
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        resetCancelled();
        long startTime = System.currentTimeMillis();
        String correlationId = LogSanitizer.newCorrelationId();

        if (!isConfigured()) {
            LOG.warn("[%s] OpenAI API key is not configured", correlationId); //$NON-NLS-1$
            return CompletableFuture.failedFuture(
                    new LlmProviderException("OpenAI API key is not configured")); //$NON-NLS-1$
        }

        LOG.info("[%s] OpenAI complete: model=%s, messages=%d", //$NON-NLS-1$
                correlationId, getModel(), request.getMessages().size());

        String requestBody = buildRequestBody(request, false);
        HttpRequest httpRequest = createPostRequest(getApiUrl() + "/chat/completions") //$NON-NLS-1$
                .header("Authorization", "Bearer " + getApiKey()) //$NON-NLS-1$ //$NON-NLS-2$
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        return sendAsync(httpRequest)
                .thenApply(response -> {
                    LlmResponse llmResponse = parseResponse(response);
                    long duration = System.currentTimeMillis() - startTime;
                    LOG.info("[%s] OpenAI response received in %s, tokens: %d/%d", //$NON-NLS-1$
                            correlationId, LogSanitizer.formatDuration(duration),
                            llmResponse.getUsage() != null ? llmResponse.getUsage().getPromptTokens() : 0,
                            llmResponse.getUsage() != null ? llmResponse.getUsage().getCompletionTokens() : 0);
                    return llmResponse;
                })
                .whenComplete((result, error) -> {
                    if (error != null) {
                        LOG.error("[%s] OpenAI request failed after %s: %s", //$NON-NLS-1$
                                correlationId, LogSanitizer.formatDuration(System.currentTimeMillis() - startTime),
                                error.getMessage());
                    }
                });
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
        resetCancelled();
        streamCompletionSent = false; // Reset for new stream
        streamingToolCalls.clear(); // Clear any leftover tool calls
        long startTime = System.currentTimeMillis();
        String correlationId = LogSanitizer.newCorrelationId();

        if (!isConfigured()) {
            LOG.warn("[%s] OpenAI API key is not configured (stream)", correlationId); //$NON-NLS-1$
            throw new LlmProviderException("OpenAI API key is not configured"); //$NON-NLS-1$
        }

        LOG.info("[%s] OpenAI streamComplete: model=%s, messages=%d", //$NON-NLS-1$
                correlationId, getModel(), request.getMessages().size());

        String requestBody = buildRequestBody(request, true);
        HttpRequest httpRequest = createPostRequest(getApiUrl() + "/chat/completions") //$NON-NLS-1$
                .header("Authorization", "Bearer " + getApiKey()) //$NON-NLS-1$ //$NON-NLS-2$
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
                        error[0] = new LlmProviderException("Failed to stream from OpenAI API", ex); //$NON-NLS-1$
                    }
                    LOG.error("[%s] OpenAI stream error: %s", correlationId, error[0].getMessage()); //$NON-NLS-1$
                    consumer.accept(LlmStreamChunk.error(error[0].getMessage()));
                }
        ).join(); // Block here to maintain method contract, but streaming happens async

        long duration = System.currentTimeMillis() - startTime;
        if (error[0] != null) {
            LOG.error("[%s] OpenAI stream failed after %s", correlationId, LogSanitizer.formatDuration(duration)); //$NON-NLS-1$
            throw error[0];
        }
        LOG.info("[%s] OpenAI stream completed in %s", correlationId, LogSanitizer.formatDuration(duration)); //$NON-NLS-1$
    }

    // Accumulate tool calls during streaming (they arrive in chunks)
    private final List<StreamingToolCall> streamingToolCalls = new ArrayList<>();
    // Track if completion has already been signaled (to avoid double completion)
    private boolean streamCompletionSent = false;

    private void processStreamLine(String line, Consumer<LlmStreamChunk> consumer, Runnable complete) {
        if (!line.startsWith("data: ")) { //$NON-NLS-1$
            return;
        }

        String data = line.substring(6);
        if ("[DONE]".equals(data)) { //$NON-NLS-1$
            // If completion was already sent (e.g., for tool_calls), just run the callback
            if (streamCompletionSent) {
                streamCompletionSent = false; // Reset for next stream
                complete.run();
                return;
            }

            // Finalize any accumulated tool calls that weren't sent yet
            if (!streamingToolCalls.isEmpty()) {
                List<ToolCall> toolCalls = finalizeStreamingToolCalls();
                consumer.accept(LlmStreamChunk.toolCalls(toolCalls));
                streamingToolCalls.clear();
                consumer.accept(LlmStreamChunk.complete(LlmResponse.FINISH_REASON_TOOL_USE));
            } else {
                consumer.accept(LlmStreamChunk.complete("stop")); //$NON-NLS-1$
            }
            complete.run();
            return;
        }

        try {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            JsonArray choices = event.getAsJsonArray("choices"); //$NON-NLS-1$

            if (!choices.isEmpty()) {
                JsonObject choice = choices.get(0).getAsJsonObject();

                if (choice.has("delta")) { //$NON-NLS-1$
                    JsonObject delta = choice.getAsJsonObject("delta"); //$NON-NLS-1$

                    // Handle regular content
                    if (delta.has("content") && !delta.get("content").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                        String content = delta.get("content").getAsString(); //$NON-NLS-1$
                        consumer.accept(LlmStreamChunk.content(content));
                    }

                    // Handle tool calls (streamed incrementally)
                    if (delta.has("tool_calls")) { //$NON-NLS-1$
                        processStreamingToolCalls(delta.getAsJsonArray("tool_calls")); //$NON-NLS-1$
                    }
                }

                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                    String reason = choice.get("finish_reason").getAsString(); //$NON-NLS-1$

                    // If finish reason is tool_calls, send accumulated tool calls
                    if ("tool_calls".equals(reason) && !streamingToolCalls.isEmpty()) { //$NON-NLS-1$
                        List<ToolCall> toolCalls = finalizeStreamingToolCalls();
                        consumer.accept(LlmStreamChunk.toolCalls(toolCalls));
                        streamingToolCalls.clear();
                        consumer.accept(LlmStreamChunk.complete(LlmResponse.FINISH_REASON_TOOL_USE));
                        streamCompletionSent = true; // Mark completion as sent
                    } else if (!streamCompletionSent) {
                        consumer.accept(LlmStreamChunk.complete(reason));
                        streamCompletionSent = true;
                    }
                    // Note: complete.run() will be called on [DONE]
                }
            }
        } catch (Exception e) {
            // Skip malformed lines
        }
    }

    private void processStreamingToolCalls(JsonArray toolCallsDelta) {
        for (JsonElement element : toolCallsDelta) {
            JsonObject callDelta = element.getAsJsonObject();
            int index = callDelta.get("index").getAsInt(); //$NON-NLS-1$

            // Ensure we have space for this index
            while (streamingToolCalls.size() <= index) {
                streamingToolCalls.add(new StreamingToolCall());
            }

            StreamingToolCall tc = streamingToolCalls.get(index);

            if (callDelta.has("id")) { //$NON-NLS-1$
                tc.id = callDelta.get("id").getAsString(); //$NON-NLS-1$
            }

            if (callDelta.has("function")) { //$NON-NLS-1$
                JsonObject functionDelta = callDelta.getAsJsonObject("function"); //$NON-NLS-1$
                if (functionDelta.has("name")) { //$NON-NLS-1$
                    tc.name = functionDelta.get("name").getAsString(); //$NON-NLS-1$
                }
                if (functionDelta.has("arguments")) { //$NON-NLS-1$
                    tc.arguments.append(functionDelta.get("arguments").getAsString()); //$NON-NLS-1$
                }
            }
        }
    }

    private List<ToolCall> finalizeStreamingToolCalls() {
        List<ToolCall> result = new ArrayList<>();
        for (StreamingToolCall stc : streamingToolCalls) {
            if (stc.id != null && stc.name != null) {
                result.add(new ToolCall(stc.id, stc.name, stc.arguments.toString()));
            }
        }
        return result;
    }

    /**
     * Helper class to accumulate streamed tool call data.
     */
    private static class StreamingToolCall {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    private String buildRequestBody(LlmRequest request, boolean stream) {
        JsonObject body = new JsonObject();

        String model = request.getModel() != null ? request.getModel() : getModel();
        body.addProperty("model", model); //$NON-NLS-1$
        body.addProperty("max_tokens", //$NON-NLS-1$
                request.getMaxTokens() > 0 ? request.getMaxTokens() : getMaxTokens());
        body.addProperty("temperature", request.getTemperature()); //$NON-NLS-1$

        if (stream) {
            body.addProperty("stream", true); //$NON-NLS-1$
        }

        // Build messages array with tool call support
        JsonArray messages = new JsonArray();
        List<LlmMessage> sanitizedMessages = LlmConversationSanitizer
                .sanitizeForOpenAiToolCalls(request.getMessages());
        for (LlmMessage msg : sanitizedMessages) {
            messages.add(serializeMessage(msg));
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

            // Add tool_choice
            if (request.getToolChoice() != null) {
                body.addProperty("tool_choice", serializeToolChoice(request.getToolChoice())); //$NON-NLS-1$
            }
        }

        String json = gson.toJson(body);
        LOG.debug("Request: model=%s, messages=%d, tools=%d", //$NON-NLS-1$
                model, sanitizedMessages.size(), request.hasTools() ? request.getTools().size() : 0);
        // Log request body for debugging (truncate if too long)
        if (json.length() < 5000) {
            LOG.debug("Request body: %s", json); //$NON-NLS-1$
        } else {
            LOG.debug("Request body length: %d (truncated)", json.length()); //$NON-NLS-1$
        }
        return json;
    }

    private JsonObject serializeMessage(LlmMessage msg) {
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("role", msg.getRole().getValue()); //$NON-NLS-1$

        if (msg.getRole() == LlmMessage.Role.TOOL) {
            // Tool result message
            msgObj.addProperty("tool_call_id", msg.getToolCallId()); //$NON-NLS-1$
            msgObj.addProperty("content", msg.getContent()); //$NON-NLS-1$
        } else if (msg.hasToolCalls()) {
            // Assistant message with tool calls
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
            // Regular message
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

        // Parse the parameters schema JSON
        try {
            JsonElement params = JsonParser.parseString(tool.getParametersSchema());
            functionObj.add("parameters", params); //$NON-NLS-1$
        } catch (Exception e) {
            // Fallback to empty object
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

    private LlmResponse parseResponse(HttpResponse<String> response) {
        if (!isSuccess(response.statusCode())) {
            LOG.error("API error: status=%d, body=%s", response.statusCode(), response.body()); //$NON-NLS-1$
            throw parseError(response);
        }

        LOG.debug("Response status=%d", response.statusCode()); //$NON-NLS-1$
        // Log raw response for debugging tool calls
        String responseBody = response.body();
        if (responseBody != null && responseBody.length() < 5000) {
            LOG.debug("Raw response body: %s", responseBody); //$NON-NLS-1$
        } else {
            LOG.debug("Raw response body length: %d", responseBody != null ? responseBody.length() : 0); //$NON-NLS-1$
        }

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices"); //$NON-NLS-1$

            if (choices.isEmpty()) {
                throw new LlmProviderException("No choices in response"); //$NON-NLS-1$
            }

            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message"); //$NON-NLS-1$

            // Log message structure for debugging
            LOG.debug("Message keys: %s", message.keySet()); //$NON-NLS-1$
            if (message.has("tool_calls")) { //$NON-NLS-1$
                LOG.debug("tool_calls found in message"); //$NON-NLS-1$
            } else {
                LOG.debug("tool_calls NOT found in message"); //$NON-NLS-1$
            }

            // Handle content - may be null when tool_calls are present
            String content = null;
            if (message.has("content") && !message.get("content").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                content = message.get("content").getAsString(); //$NON-NLS-1$
            }

            String finishReason = choice.has("finish_reason") //$NON-NLS-1$
                    ? choice.get("finish_reason").getAsString() : null; //$NON-NLS-1$

            String model = json.has("model") ? json.get("model").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$

            // Parse usage
            LlmResponse.Usage usage = null;
            if (json.has("usage")) { //$NON-NLS-1$
                JsonObject usageJson = json.getAsJsonObject("usage"); //$NON-NLS-1$
                int promptTokens = usageJson.get("prompt_tokens").getAsInt(); //$NON-NLS-1$
                int completionTokens = usageJson.get("completion_tokens").getAsInt(); //$NON-NLS-1$
                int totalTokens = usageJson.get("total_tokens").getAsInt(); //$NON-NLS-1$
                int cachedPromptTokens = 0;
                if (usageJson.has("prompt_tokens_details") && usageJson.get("prompt_tokens_details").isJsonObject()) { //$NON-NLS-1$ //$NON-NLS-2$
                    JsonObject details = usageJson.getAsJsonObject("prompt_tokens_details"); //$NON-NLS-1$
                    if (details.has("cached_tokens") && !details.get("cached_tokens").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                        cachedPromptTokens = details.get("cached_tokens").getAsInt(); //$NON-NLS-1$
                    }
                }
                usage = new LlmResponse.Usage(promptTokens, cachedPromptTokens, completionTokens, totalTokens);
            }

            // Parse tool calls if present
            List<ToolCall> toolCalls = null;
            if (message.has("tool_calls")) { //$NON-NLS-1$
                JsonArray toolCallsJson = message.getAsJsonArray("tool_calls"); //$NON-NLS-1$
                toolCalls = parseToolCalls(toolCallsJson);

                LOG.debug("Parsed %d tool calls", toolCalls.size()); //$NON-NLS-1$
                for (ToolCall tc : toolCalls) {
                    LOG.debug("  Tool call: %s(%s)", tc.getName(), tc.getArguments()); //$NON-NLS-1$
                }

                // Set finish reason to tool_use if we have tool calls
                if (!toolCalls.isEmpty() && !"tool_calls".equals(finishReason)) { //$NON-NLS-1$
                    finishReason = LlmResponse.FINISH_REASON_TOOL_USE;
                }
            } else {
                // Some OpenAI-compatible providers return an empty "content" while putting the
                // visible answer into a non-standard "reasoning_content" field.
                // Use it as a fallback only for the final answer (no tool calls).
                if ((content == null || content.isEmpty())
                        && message.has("reasoning_content") //$NON-NLS-1$
                        && !message.get("reasoning_content").isJsonNull()) { //$NON-NLS-1$
                    String rc = message.get("reasoning_content").getAsString(); //$NON-NLS-1$
                    if (rc != null && !rc.isEmpty()) {
                        LOG.debug("Using reasoning_content as content fallback (provider returned empty content)"); //$NON-NLS-1$
                        content = rc;
                    }
                }
            }

            LOG.debug("Response parsed: finishReason=%s, hasContent=%b, toolCalls=%d", //$NON-NLS-1$
                    finishReason, content != null && !content.isEmpty(),
                    toolCalls != null ? toolCalls.size() : 0);

            return new LlmResponse(content, model, usage, finishReason, toolCalls);
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to parse response", e); //$NON-NLS-1$
            throw new LlmProviderException("Failed to parse OpenAI response", e); //$NON-NLS-1$
        }
    }

    private List<ToolCall> parseToolCalls(JsonArray toolCallsJson) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonElement element : toolCallsJson) {
            JsonObject callObj = element.getAsJsonObject();
            String id = callObj.get("id").getAsString(); //$NON-NLS-1$
            JsonObject function = callObj.getAsJsonObject("function"); //$NON-NLS-1$
            String name = function.get("name").getAsString(); //$NON-NLS-1$
            String arguments = function.get("arguments").getAsString(); //$NON-NLS-1$
            toolCalls.add(new ToolCall(id, name, arguments));
        }
        return toolCalls;
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
                "OpenAI API error: " + response.statusCode(), null, response.statusCode(), null); //$NON-NLS-1$
    }
}
