/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderException;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Dynamic LLM provider that works with any configuration.
 *
 * <p>Supports OpenAI-compatible, Anthropic, and Ollama APIs based on
 * the provider type specified in the configuration.</p>
 */
public class DynamicLlmProvider implements ILlmProvider {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(DynamicLlmProvider.class);

    private final LlmProviderConfig config;
    private final HttpClient httpClient;
    private final Gson gson;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private CompletableFuture<?> currentRequest;
    private LlmRequest currentLlmRequest; // Store for tool support

    /**
     * Creates a new dynamic provider with the given configuration.
     */
    public DynamicLlmProvider(LlmProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                // vLLM/uvicorn deployments are commonly exposed over plain HTTP and can fail
                // with Java HttpClient HTTP/2 (h2c) by not parsing the request body.
                // HTTP/1.1 is the most compatible default for OpenAI-compatible endpoints.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.gson = new Gson();
    }

    @Override
    public String getId() {
        return config.getId();
    }

    @Override
    public String getDisplayName() {
        return config.getName();
    }

    @Override
    public boolean isConfigured() {
        return config.isConfigured();
    }

    @Override
    public boolean supportsStreaming() {
        return config.isStreamingEnabled();
    }

    /**
     * Returns the underlying configuration.
     */
    public LlmProviderConfig getConfig() {
        return config;
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        long startTime = System.currentTimeMillis();
        String correlationId = LogSanitizer.newCorrelationId();

        if (!isConfigured()) {
            LOG.warn("[%s] Provider not configured: %s", correlationId, config.getName()); //$NON-NLS-1$
            return CompletableFuture.failedFuture(
                    new LlmProviderException("Provider not configured: " + config.getName())); //$NON-NLS-1$
        }

        cancelled.set(false);
        currentLlmRequest = request; // Store for tool support

        LOG.info("[%s] DynamicProvider complete: provider=%s, model=%s, messages=%d", //$NON-NLS-1$
                correlationId, config.getName(), config.getModel(), request.getMessages().size());

        String requestBody = buildRequestBody(request, false);
        LOG.debug("[%s] Request body: %s", correlationId, //$NON-NLS-1$
                requestBody.length() < 5000 ? requestBody : "(truncated, length=" + requestBody.length() + ")"); //$NON-NLS-1$

        HttpRequest httpRequest = buildHttpRequest(requestBody);

        currentRequest = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (cancelled.get()) {
                        throw new CancellationException("Request cancelled"); //$NON-NLS-1$
                    }
                    long duration = System.currentTimeMillis() - startTime;
                    LOG.debug("[%s] Response status: %d in %s", correlationId, response.statusCode(), //$NON-NLS-1$
                            LogSanitizer.formatDuration(duration));
                    LOG.debug("[%s] Response body: %s", correlationId, //$NON-NLS-1$
                            response.body().length() < 5000 ? response.body() : "(truncated, length=" + response.body().length() + ")"); //$NON-NLS-1$
                    return parseResponse(response);
                })
                .whenComplete((result, error) -> {
                    long duration = System.currentTimeMillis() - startTime;
                    if (error != null) {
                        LOG.error("[%s] DynamicProvider request failed after %s: %s", //$NON-NLS-1$
                                correlationId, LogSanitizer.formatDuration(duration), error.getMessage());
                    } else {
                        LOG.info("[%s] DynamicProvider response received in %s", //$NON-NLS-1$
                                correlationId, LogSanitizer.formatDuration(duration));
                    }
                });

        return currentRequest.thenApply(obj -> (LlmResponse) obj);
    }

    /** Accumulated tool calls during streaming, indexed by tool call index */
    private final java.util.Map<Integer, StreamingToolCall> streamingToolCalls = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Helper class to accumulate tool call data during streaming.
     */
    private static class StreamingToolCall {
        String id = ""; //$NON-NLS-1$
        String name = ""; //$NON-NLS-1$
        StringBuilder arguments = new StringBuilder();

        ToolCall toToolCall() {
            return new ToolCall(id, name, arguments.toString());
        }
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
        long startTime = System.currentTimeMillis();
        String correlationId = LogSanitizer.newCorrelationId();

        if (!isConfigured()) {
            LOG.warn("[%s] Provider not configured: %s (stream)", correlationId, config.getName()); //$NON-NLS-1$
            throw new LlmProviderException("Provider not configured: " + config.getName()); //$NON-NLS-1$
        }

        cancelled.set(false);
        streamingToolCalls.clear(); // Reset accumulated tool calls

        LOG.info("[%s] DynamicProvider streamComplete: provider=%s, model=%s, messages=%d", //$NON-NLS-1$
                correlationId, config.getName(), config.getModel(), request.getMessages().size());

        String requestBody = buildRequestBody(request, true);
        HttpRequest httpRequest = buildHttpRequest(requestBody);

        try {
            // Use BodyHandlers.ofLines() for true SSE streaming
            HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());

            if (cancelled.get()) {
                LOG.debug("[%s] Stream cancelled before processing", correlationId); //$NON-NLS-1$
                return;
            }

            if (response.statusCode() != 200) {
                // For error case, collect body for error message
                String errorBody = response.body().collect(java.util.stream.Collectors.joining("\n")); //$NON-NLS-1$
                LOG.error("[%s] Stream API error: status=%d", correlationId, response.statusCode()); //$NON-NLS-1$
                throw new LlmProviderException("API error: " + response.statusCode() + " - " + errorBody); //$NON-NLS-1$ //$NON-NLS-2$
            }

            // Track finish reason from stream
            final String[] streamFinishReason = { "stop" }; //$NON-NLS-1$

            // Process lines as they arrive
            response.body().forEach(line -> {
                if (cancelled.get()) {
                    return;
                }
                String finishReason = processStreamLine(line, consumer);
                if (finishReason != null) {
                    streamFinishReason[0] = finishReason;
                }
            });

            // If we accumulated tool calls, send them as a final chunk
            if (!streamingToolCalls.isEmpty() && !cancelled.get()) {
                List<ToolCall> toolCalls = new ArrayList<>();
                // Sort by index to maintain order, filter out invalid tool calls (empty name)
                streamingToolCalls.entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            ToolCall tc = entry.getValue().toToolCall();
                            // Skip tool calls with empty or invalid name (can happen with some models)
                            if (tc.getName() == null || tc.getName().trim().isEmpty()) {
                                LOG.warn("[%s] Skipping invalid tool call with empty name at index %d, args: %s", //$NON-NLS-1$
                                        correlationId, entry.getKey(), tc.getArguments());
                                return;
                            }
                            toolCalls.add(tc);
                        });

                if (!toolCalls.isEmpty()) {
                    LOG.debug("[%s] Stream completed with %d valid tool calls", correlationId, toolCalls.size()); //$NON-NLS-1$
                    for (ToolCall tc : toolCalls) {
                        LOG.debug("[%s]   Tool call: %s(%s)", correlationId, tc.getName(), //$NON-NLS-1$
                                tc.getArguments().length() > 100 ? tc.getArguments().substring(0, 100) + "..." : tc.getArguments()); //$NON-NLS-1$
                    }
                    consumer.accept(LlmStreamChunk.toolCalls(toolCalls));
                } else {
                    LOG.warn("[%s] All %d streamed tool calls were invalid, ignoring", correlationId, streamingToolCalls.size()); //$NON-NLS-1$
                }
            }

            // Send final chunk if not already done
            if (!cancelled.get()) {
                consumer.accept(LlmStreamChunk.complete(streamFinishReason[0]));
            }

            long duration = System.currentTimeMillis() - startTime;
            LOG.info("[%s] DynamicProvider stream completed in %s", correlationId, LogSanitizer.formatDuration(duration)); //$NON-NLS-1$

        } catch (IOException | InterruptedException e) {
            long duration = System.currentTimeMillis() - startTime;
            if (!cancelled.get()) {
                LOG.error("[%s] DynamicProvider stream failed after %s: %s", //$NON-NLS-1$
                        correlationId, LogSanitizer.formatDuration(duration), e.getMessage());
                throw new LlmProviderException("Stream request failed: " + e.getMessage(), e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Processes a single SSE line.
     *
     * @return the finish_reason if found, null otherwise
     */
    private String processStreamLine(String line, Consumer<LlmStreamChunk> consumer) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        // SSE lines can be "data: {...}" or "data:{...}" (with or without a space).
        if (line.startsWith("data:")) { //$NON-NLS-1$
            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) { //$NON-NLS-1$
                return null;
            }

            try {
                // Some providers send heartbeat chunks like "null". Ignore non-object payloads.
                JsonElement parsed = JsonParser.parseString(data);
                if (parsed == null || parsed.isJsonNull() || !parsed.isJsonObject()) {
                    return null;
                }
                JsonObject json = parsed.getAsJsonObject();

                // Extract and send text content
                String chunk = extractChunkContent(json);
                if (chunk != null && !chunk.isEmpty()) {
                    consumer.accept(LlmStreamChunk.content(chunk));
                }

                // Extract and accumulate tool calls
                extractAndAccumulateToolCalls(json);

                // Return finish_reason if present
                return extractFinishReason(json);

            } catch (Exception e) {
                VibeCorePlugin.logWarn("Failed to parse stream chunk: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Extracts and accumulates tool calls from a streaming chunk.
     * Tool calls come in pieces: first chunk has id and name, subsequent chunks have argument fragments.
     */
    private void extractAndAccumulateToolCalls(JsonObject json) {
        JsonArray choices = json.getAsJsonArray("choices"); //$NON-NLS-1$
        if (choices == null || choices.size() == 0) {
            return;
        }

        JsonObject choice = choices.get(0).getAsJsonObject();
        // OpenAI streaming format: choices[0].delta.tool_calls
        // Some OpenAI-compatible providers may put tool_calls under choices[0].message.tool_calls.
        JsonArray toolCallsArray = null;
        JsonObject delta = choice.getAsJsonObject("delta"); //$NON-NLS-1$
        if (delta != null && delta.has("tool_calls")) { //$NON-NLS-1$
            toolCallsArray = delta.getAsJsonArray("tool_calls"); //$NON-NLS-1$
        } else {
            JsonObject message = choice.getAsJsonObject("message"); //$NON-NLS-1$
            if (message != null && message.has("tool_calls")) { //$NON-NLS-1$
                toolCallsArray = message.getAsJsonArray("tool_calls"); //$NON-NLS-1$
            }
        }

        if (toolCallsArray == null) {
            return;
        }

        for (JsonElement element : toolCallsArray) {
            JsonObject tcObj = element.getAsJsonObject();

            // Get index (required for tracking multiple parallel tool calls)
            int index = tcObj.has("index") ? tcObj.get("index").getAsInt() : 0; //$NON-NLS-1$

            // Get or create accumulator for this index
            StreamingToolCall accumulator = streamingToolCalls.computeIfAbsent(index, k -> new StreamingToolCall());

            // Accumulate id (usually in first chunk)
            if (tcObj.has("id") && !tcObj.get("id").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                accumulator.id = mergeStableField(accumulator.id, tcObj.get("id").getAsString()); //$NON-NLS-1$
            }

            // Accumulate function name and arguments
            if (tcObj.has("function")) { //$NON-NLS-1$
                JsonObject function = tcObj.getAsJsonObject("function"); //$NON-NLS-1$

                if (function.has("name") && !function.get("name").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                    accumulator.name = mergeStableField(accumulator.name, function.get("name").getAsString()); //$NON-NLS-1$
                }

                if (function.has("arguments") && !function.get("arguments").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                    accumulator.arguments.append(function.get("arguments").getAsString()); //$NON-NLS-1$
                }
            }
        }
    }

    private String mergeStableField(String current, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return incoming;
        }
        if (current.equals(incoming) || current.endsWith(incoming)) {
            return current;
        }
        if (incoming.endsWith(current)) {
            return incoming;
        }
        // Some providers resend full value in later chunks; prefer the latest stable value.
        return incoming;
    }

    /**
     * Extracts finish_reason from a streaming chunk.
     */
    private String extractFinishReason(JsonObject json) {
        JsonArray choices = json.getAsJsonArray("choices"); //$NON-NLS-1$
        if (choices == null || choices.size() == 0) {
            return null;
        }

        JsonObject choice = choices.get(0).getAsJsonObject();
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            return choice.get("finish_reason").getAsString(); //$NON-NLS-1$
        }
        return null;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        if (currentRequest != null) {
            currentRequest.cancel(true);
        }
    }

    @Override
    public void dispose() {
        cancel();
    }

    /**
     * Builds the HTTP request with appropriate headers.
     */
    private HttpRequest buildHttpRequest(String body) {
        String url = config.getChatEndpointUrl();
        int timeoutSeconds = getRequestTimeoutSeconds();

        LOG.info("=== HTTP REQUEST BUILD ==="); //$NON-NLS-1$
        LOG.info("URL: %s", url); //$NON-NLS-1$
        LOG.info("Base URL from config: %s", config.getBaseUrl()); //$NON-NLS-1$
        LOG.info("Provider type: %s", config.getType()); //$NON-NLS-1$
        LOG.info("Request timeout (seconds): %d", timeoutSeconds); //$NON-NLS-1$

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$

        // Add authorization header based on provider type
        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            LOG.info("API Key length: %d, first 8 chars: %s...", //$NON-NLS-1$
                    apiKey.length(), apiKey.substring(0, Math.min(8, apiKey.length())));
            switch (config.getType()) {
                case ANTHROPIC:
                    builder.header("x-api-key", apiKey); //$NON-NLS-1$
                    builder.header("anthropic-version", "2023-06-01"); //$NON-NLS-1$ //$NON-NLS-2$
                    LOG.info("Auth: x-api-key header (Anthropic)"); //$NON-NLS-1$
                    break;
                case OPENAI_COMPATIBLE:
                case OLLAMA:
                default:
                    builder.header("Authorization", "Bearer " + apiKey); //$NON-NLS-1$ //$NON-NLS-2$
                    LOG.info("Auth: Bearer token (OpenAI compatible)"); //$NON-NLS-1$
                    break;
            }
        } else {
            LOG.warn("No API key configured!"); //$NON-NLS-1$
        }

        // Add custom headers
        config.getCustomHeaders().forEach((key, value) -> {
            LOG.info("Custom header: %s = %s", key, value); //$NON-NLS-1$
            builder.header(key, value);
        });

        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        LOG.info("=== END HTTP REQUEST BUILD ==="); //$NON-NLS-1$
        return builder.build();
    }

    private int getRequestTimeoutSeconds() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        return prefs.getInt(VibePreferenceConstants.PREF_REQUEST_TIMEOUT, 60);
    }

    /**
     * Builds the request body based on provider type.
     */
    private String buildRequestBody(LlmRequest request, boolean stream) {
        JsonObject body = new JsonObject();

        switch (config.getType()) {
            case ANTHROPIC:
                return buildAnthropicRequestBody(request, stream);
            case OLLAMA:
                return buildOllamaRequestBody(request, stream);
            case OPENAI_COMPATIBLE:
            default:
                return buildOpenAiRequestBody(request, stream);
        }
    }

    /**
     * Builds OpenAI-compatible request body.
     */
    private String buildOpenAiRequestBody(LlmRequest request, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel()); //$NON-NLS-1$
        body.addProperty("max_tokens", config.getMaxTokens()); //$NON-NLS-1$
        body.addProperty("stream", stream); //$NON-NLS-1$

        JsonArray messages = new JsonArray();

        // Add all messages (including system messages and tool results)
        for (LlmMessage msg : request.getMessages()) {
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
                String toolChoice = serializeToolChoice(request.getToolChoice());
                body.addProperty("tool_choice", toolChoice); //$NON-NLS-1$
            }
        }

        return gson.toJson(body);
    }

    /**
     * Serializes a message to JSON, handling tool calls and tool results.
     */
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

    /**
     * Serializes a tool definition to JSON.
     */
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

    /**
     * Serializes tool choice to string.
     */
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

    /**
     * Builds Anthropic API request body.
     */
    private String buildAnthropicRequestBody(LlmRequest request, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel()); //$NON-NLS-1$
        body.addProperty("max_tokens", config.getMaxTokens()); //$NON-NLS-1$
        body.addProperty("stream", stream); //$NON-NLS-1$

        // Extract system message (Anthropic uses separate "system" field)
        JsonArray messages = new JsonArray();
        for (LlmMessage msg : request.getMessages()) {
            if (msg.getRole() == LlmMessage.Role.SYSTEM) {
                // System message is a separate field in Anthropic API
                body.addProperty("system", msg.getContent()); //$NON-NLS-1$
            } else {
                JsonObject msgObj = new JsonObject();
                msgObj.addProperty("role", msg.getRole().getValue()); //$NON-NLS-1$
                msgObj.addProperty("content", msg.getContent()); //$NON-NLS-1$
                messages.add(msgObj);
            }
        }

        body.add("messages", messages); //$NON-NLS-1$
        return gson.toJson(body);
    }

    /**
     * Builds Ollama API request body.
     */
    private String buildOllamaRequestBody(LlmRequest request, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel()); //$NON-NLS-1$
        body.addProperty("stream", stream); //$NON-NLS-1$

        JsonArray messages = new JsonArray();

        // Add all messages (including system messages)
        for (LlmMessage msg : request.getMessages()) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole().getValue()); //$NON-NLS-1$
            msgObj.addProperty("content", msg.getContent()); //$NON-NLS-1$
            messages.add(msgObj);
        }

        body.add("messages", messages); //$NON-NLS-1$
        return gson.toJson(body);
    }

    /**
     * Parses the API response based on provider type.
     */
    private LlmResponse parseResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new LlmProviderException("API error: " + response.statusCode() + " - " + response.body()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            switch (config.getType()) {
                case ANTHROPIC:
                    return parseAnthropicResponse(json);
                case OLLAMA:
                    return parseOllamaResponse(json);
                case OPENAI_COMPATIBLE:
                default:
                    return parseOpenAiResponse(json);
            }
        } catch (Exception e) {
            throw new LlmProviderException("Failed to parse response: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * Parses OpenAI-compatible response.
     */
    private LlmResponse parseOpenAiResponse(JsonObject json) {
        JsonArray choices = json.getAsJsonArray("choices"); //$NON-NLS-1$
        if (choices == null || choices.size() == 0) {
            throw new LlmProviderException("No choices in response"); //$NON-NLS-1$
        }

        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message"); //$NON-NLS-1$

        // Log message structure for debugging
        LOG.debug("Message keys: %s", message.keySet()); //$NON-NLS-1$

        // Handle content - may be null when tool_calls are present
        String content = null;
        if (message.has("content") && !message.get("content").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            content = message.get("content").getAsString(); //$NON-NLS-1$
        }

        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() ? //$NON-NLS-1$ //$NON-NLS-2$
                choice.get("finish_reason").getAsString() : "stop"; //$NON-NLS-1$ //$NON-NLS-2$

        // Parse tool calls if present
        List<ToolCall> toolCalls = null;
        if (message.has("tool_calls")) { //$NON-NLS-1$
            LOG.debug("tool_calls found in message"); //$NON-NLS-1$
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
            LOG.debug("tool_calls NOT found in message"); //$NON-NLS-1$

            // Some OpenAI-compatible providers return an empty "content" while putting the
            // visible answer into a non-standard "reasoning_content" field. This leads to
            // the UI showing an empty final message after tool execution.
            //
            // We only use this fallback when there are no tool calls (i.e., final answer).
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

        return new LlmResponse(content, config.getModel(), null, finishReason, toolCalls);
    }

    /**
     * Parses tool calls from JSON array.
     */
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

    /**
     * Parses Anthropic response.
     */
    private LlmResponse parseAnthropicResponse(JsonObject json) {
        JsonArray content = json.getAsJsonArray("content"); //$NON-NLS-1$
        if (content == null || content.size() == 0) {
            throw new LlmProviderException("No content in response"); //$NON-NLS-1$
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            JsonObject block = content.get(i).getAsJsonObject();
            if ("text".equals(block.get("type").getAsString())) { //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(block.get("text").getAsString()); //$NON-NLS-1$
            }
        }

        String stopReason = json.has("stop_reason") ? //$NON-NLS-1$
                json.get("stop_reason").getAsString() : "end_turn"; //$NON-NLS-1$ //$NON-NLS-2$

        return new LlmResponse(sb.toString(), config.getModel(), null, stopReason);
    }

    /**
     * Parses Ollama response.
     */
    private LlmResponse parseOllamaResponse(JsonObject json) {
        JsonObject message = json.getAsJsonObject("message"); //$NON-NLS-1$
        String content = message.get("content").getAsString(); //$NON-NLS-1$

        boolean done = json.has("done") && json.get("done").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        return new LlmResponse(content, config.getModel(), null, done ? "stop" : "length"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Extracts content from a stream chunk based on provider type.
     */
    private String extractChunkContent(JsonObject json) {
        switch (config.getType()) {
            case ANTHROPIC:
                if (json.has("delta")) { //$NON-NLS-1$
                    JsonObject delta = json.getAsJsonObject("delta"); //$NON-NLS-1$
                    if (delta.has("text")) { //$NON-NLS-1$
                        return delta.get("text").getAsString(); //$NON-NLS-1$
                    }
                }
                break;
            case OLLAMA:
                if (json.has("message")) { //$NON-NLS-1$
                    JsonObject message = json.getAsJsonObject("message"); //$NON-NLS-1$
                    if (message.has("content")) { //$NON-NLS-1$
                        return message.get("content").getAsString(); //$NON-NLS-1$
                    }
                }
                break;
            case OPENAI_COMPATIBLE:
            default:
                if (json.has("choices")) { //$NON-NLS-1$
                    JsonArray choices = json.getAsJsonArray("choices"); //$NON-NLS-1$
                    if (choices.size() > 0) {
                        JsonObject choice = choices.get(0).getAsJsonObject();
                        if (choice.has("delta")) { //$NON-NLS-1$
                            JsonObject delta = choice.getAsJsonObject("delta"); //$NON-NLS-1$
                            if (delta.has("content")) { //$NON-NLS-1$
                                return delta.get("content").getAsString(); //$NON-NLS-1$
                            }
                        }
                    }
                }
                break;
        }
        return null;
    }
}
