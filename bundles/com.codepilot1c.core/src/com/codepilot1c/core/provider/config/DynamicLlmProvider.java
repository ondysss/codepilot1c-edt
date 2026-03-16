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
    private static final int OUTBOUND_TOOL_RESULT_CHAR_LIMIT = 50_000;
    private static final int OUTBOUND_TOOL_RESULT_HEAD_CHARS = 30_000;
    private static final int OUTBOUND_TOOL_RESULT_TAIL_CHARS = 15_000;
    private static final int LARGE_REQUEST_TIMEOUT_THRESHOLD_CHARS = 80_000;
    private static final int VERY_LARGE_REQUEST_TIMEOUT_THRESHOLD_CHARS = 200_000;
    private static final int LARGE_REQUEST_TIMEOUT_SECONDS = 120;
    private static final int VERY_LARGE_REQUEST_TIMEOUT_SECONDS = 180;

    private final LlmProviderConfig config;
    private final OpenAiModelCompatibilityPolicy openAiCompatibilityPolicy;
    private final OpenAiStreamingToolCallParser streamingToolCallParser;
    private final ProviderHttpTransport httpTransport;
    private final Gson gson;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private CompletableFuture<?> currentRequest;
    private LlmRequest currentLlmRequest; // Store for tool support

    /**
     * Creates a new dynamic provider with the given configuration.
     */
    public DynamicLlmProvider(LlmProviderConfig config) {
        this.config = config;
        this.openAiCompatibilityPolicy = new OpenAiModelCompatibilityPolicy();
        this.streamingToolCallParser = new OpenAiStreamingToolCallParser();
        HttpClient client = HttpClient.newBuilder()
                // vLLM/uvicorn deployments are commonly exposed over plain HTTP and can fail
                // with Java HttpClient HTTP/2 (h2c) by not parsing the request body.
                // HTTP/1.1 is the most compatible default for OpenAI-compatible endpoints.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.httpTransport = new ProviderHttpTransport(client);
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

        ProviderExecutionPlan executionPlan = buildExecutionPlan(request, false);
        return completeWithPlan(request, executionPlan, startTime, correlationId);
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
        currentLlmRequest = request;
        streamingToolCallParser.clear();
        ProviderExecutionPlan executionPlan = buildExecutionPlan(request, true);
        OpenAiStreamingSession openAiSession = config.getType() == ProviderType.OPENAI_COMPATIBLE
                ? new OpenAiStreamingSession(correlationId, request.hasTools(), streamingToolCallParser)
                : null;
        ProviderStreamProcessingSummary summary = openAiSession != null
                ? openAiSession.getSummary()
                : new ProviderStreamProcessingSummary(correlationId, request.hasTools());

        LOG.info("[%s] DynamicProvider streamComplete: provider=%s, model=%s, messages=%d", //$NON-NLS-1$
                correlationId, config.getName(), config.getModel(), request.getMessages().size());
        if (!executionPlan.isStreaming()) {
            LOG.info("[%s] Using non-stream execution plan for streaming request: %s", correlationId, //$NON-NLS-1$
                    executionPlan.getReason() != null ? executionPlan.getReason() : "compatibility policy"); //$NON-NLS-1$
            replayResponseAsStream(request, consumer, correlationId, executionPlan);
            return;
        }

        String requestBody = buildRequestBody(request, executionPlan);
        HttpRequest httpRequest = buildHttpRequest(requestBody);

        try {
            // Use BodyHandlers.ofLines() for true SSE streaming
            HttpResponse<java.util.stream.Stream<String>> response = httpTransport.sendStreamingLines(httpRequest);

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
            final String[] streamFinishReason = { LlmResponse.FINISH_REASON_STOP };

            // Process lines as they arrive
            response.body().forEach(line -> {
                if (cancelled.get()) {
                    return;
                }
                String finishReason = processStreamLine(line, consumer, summary, openAiSession);
                if (finishReason != null) {
                    streamFinishReason[0] = finishReason;
                }
            });

            if (!cancelled.get() && summary.shouldFallbackToNonStreaming()) {
                LOG.warn("[%s] Falling back to non-streaming response handling: parseFailures=%d, opaqueChunks=%d", //$NON-NLS-1$
                        correlationId, summary.getParseFailures().get(), summary.getOpaqueChunks().get());
                streamingToolCallParser.clear();
                replayNonStreamingFallback(request, consumer, correlationId);
                return;
            }

            // Send final chunk if not already done
            if (!cancelled.get()) {
                consumer.accept(LlmStreamChunk.complete(normalizeFinishReason(streamFinishReason[0])));
            }

            long duration = System.currentTimeMillis() - startTime;
            logStreamSummary(summary, openAiSession);
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
    private String processStreamLine(String line, Consumer<LlmStreamChunk> consumer,
            ProviderStreamProcessingSummary summary, OpenAiStreamingSession openAiSession) {
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
                if (config.getType() == ProviderType.OPENAI_COMPATIBLE && openAiSession != null) {
                    return openAiSession.processLine(line, consumer);
                }
                // Some providers send heartbeat chunks like "null". Ignore non-object payloads.
                JsonElement parsed = JsonParser.parseString(data);
                if (parsed == null || parsed.isJsonNull() || !parsed.isJsonObject()) {
                    summary.getNullPayloads().incrementAndGet();
                    return null;
                }
                JsonObject json = parsed.getAsJsonObject();
                return processLegacyStreamChunk(json, consumer, summary);

            } catch (Exception e) {
                summary.getParseFailures().incrementAndGet();
                LOG.debug("[%s] Failed to parse stream chunk: %s", summary.getCorrelationId(), e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    private String processLegacyStreamChunk(JsonObject json, Consumer<LlmStreamChunk> consumer,
            ProviderStreamProcessingSummary summary) {
        String chunk = extractChunkContent(json);
        if (chunk != null && !chunk.isEmpty()) {
            summary.getContentChunks().incrementAndGet();
            consumer.accept(LlmStreamChunk.content(chunk));
        }

        String reasoningChunk = extractReasoningChunk(json);
        if (reasoningChunk != null && !reasoningChunk.isEmpty()) {
            summary.getReasoningChunks().incrementAndGet();
            consumer.accept(LlmStreamChunk.reasoning(reasoningChunk));
        }

        String finishReason = extractFinishReason(json);
        JsonArray choices = getArray(json, "choices"); //$NON-NLS-1$
        if (choices == null || choices.size() == 0) {
            summary.getMetadataChunks().incrementAndGet();
        } else if (chunk == null && reasoningChunk == null && finishReason == null) {
            summary.getOpaqueChunks().incrementAndGet();
        }
        return finishReason;
    }

    /**
     * Extracts finish_reason from a streaming chunk.
     */
    private String extractFinishReason(JsonObject json) {
        JsonArray choices = getArray(json, "choices"); //$NON-NLS-1$
        if (choices == null || choices.size() == 0) {
            return null;
        }

        JsonObject choice = getObject(choices.get(0));
        if (choice == null) {
            return null;
        }
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            return normalizeFinishReason(choice.get("finish_reason").getAsString()); //$NON-NLS-1$
        }
        return null;
    }

    private String normalizeFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isBlank()) {
            return LlmResponse.FINISH_REASON_STOP;
        }
        if ("tool_calls".equals(finishReason)) { //$NON-NLS-1$
            return LlmResponse.FINISH_REASON_TOOL_USE;
        }
        return finishReason;
    }

    private void replayNonStreamingFallback(LlmRequest request, Consumer<LlmStreamChunk> consumer, String correlationId) {
        replayResponseAsStream(buildNonStreamingFallbackRequest(request), consumer, correlationId,
                buildExecutionPlan(buildNonStreamingFallbackRequest(request), false));
    }

    private LlmRequest buildNonStreamingFallbackRequest(LlmRequest request) {
        LlmRequest.Builder builder = LlmRequest.builder()
                .messages(request.getMessages())
                .model(request.getModel())
                .maxTokens(request.getMaxTokens())
                .temperature(request.getTemperature())
                .stream(false)
                .toolChoice(request.getToolChoice());
        if (request.hasTools()) {
            builder.tools(request.getTools());
        }
        return builder.build();
    }

    private void logStreamSummary(ProviderStreamProcessingSummary summary, OpenAiStreamingSession openAiSession) {
        if (openAiSession != null) {
            openAiSession.logSummary(LOG);
            return;
        }
        if (summary.getNullPayloads().get() == 0
                && summary.getMetadataChunks().get() == 0
                && summary.getOpaqueChunks().get() == 0
                && summary.getParseFailures().get() == 0) {
            return;
        }
        LOG.debug("[%s] Stream summary: nullPayloads=%d, metadataChunks=%d, opaqueChunks=%d, parseFailures=%d, contentChunks=%d, reasoningChunks=%d, toolCallChunks=%d", //$NON-NLS-1$
                summary.getCorrelationId(),
                summary.getNullPayloads().get(),
                summary.getMetadataChunks().get(),
                summary.getOpaqueChunks().get(),
                summary.getParseFailures().get(),
                summary.getContentChunks().get(),
                summary.getReasoningChunks().get(),
                summary.getToolCallFragments().get());
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
        int timeoutSeconds = resolveRequestTimeoutSeconds(body);

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

    private int resolveRequestTimeoutSeconds(String body) {
        int baseTimeout = getRequestTimeoutSeconds();
        if (body == null) {
            return baseTimeout;
        }
        if (body.length() >= VERY_LARGE_REQUEST_TIMEOUT_THRESHOLD_CHARS) {
            return Math.max(baseTimeout, VERY_LARGE_REQUEST_TIMEOUT_SECONDS);
        }
        if (body.length() >= LARGE_REQUEST_TIMEOUT_THRESHOLD_CHARS) {
            return Math.max(baseTimeout, LARGE_REQUEST_TIMEOUT_SECONDS);
        }
        return baseTimeout;
    }

    private CompletableFuture<LlmResponse> completeWithPlan(LlmRequest request,
            ProviderExecutionPlan executionPlan, long startTime, String correlationId) {
        if (executionPlan.getReason() != null) {
            LOG.debug("[%s] Provider execution plan: stream=%b, reason=%s", correlationId, //$NON-NLS-1$
                    executionPlan.isStreaming(), executionPlan.getReason());
        }

        String requestBody = buildRequestBody(request, executionPlan);
        LOG.debug("[%s] Request body: %s", correlationId, //$NON-NLS-1$
                requestBody.length() < 5000 ? requestBody : "(truncated, length=" + requestBody.length() + ")"); //$NON-NLS-1$

        HttpRequest httpRequest = buildHttpRequest(requestBody);

        currentRequest = httpTransport.sendStringAsync(httpRequest)
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

    private ProviderExecutionPlan buildExecutionPlan(LlmRequest request, boolean requestedStreaming) {
        if (config.getType() != ProviderType.OPENAI_COMPATIBLE) {
            return ProviderExecutionPlan.streaming(requestedStreaming && config.isStreamingEnabled());
        }
        return openAiCompatibilityPolicy.plan(config, request, requestedStreaming);
    }

    private void replayResponseAsStream(LlmRequest request, Consumer<LlmStreamChunk> consumer,
            String correlationId, ProviderExecutionPlan executionPlan) {
        if (cancelled.get()) {
            return;
        }

        long fallbackStartTime = System.currentTimeMillis();
        LlmResponse response = completeWithPlan(request, executionPlan, fallbackStartTime,
                correlationId + "-replay").join(); //$NON-NLS-1$

        if (cancelled.get()) {
            return;
        }

        LOG.info("[%s] Non-stream replay completed: hasContent=%b, toolCalls=%d, finishReason=%s", //$NON-NLS-1$
                correlationId,
                response.getContent() != null && !response.getContent().isEmpty(),
                response.getToolCalls().size(),
                response.getFinishReason());

        if (response.hasReasoning() && !cancelled.get()) {
            consumer.accept(LlmStreamChunk.reasoning(response.getReasoningContent()));
        }
        if (response.getContent() != null && !response.getContent().isEmpty() && !cancelled.get()) {
            consumer.accept(LlmStreamChunk.content(response.getContent()));
        }
        if (response.hasToolCalls() && !cancelled.get()) {
            consumer.accept(LlmStreamChunk.toolCalls(response.getToolCalls()));
        }
        if (!cancelled.get()) {
            consumer.accept(LlmStreamChunk.complete(normalizeFinishReason(response.getFinishReason())));
        }
    }

    /**
     * Builds the request body based on provider type.
     */
    private String buildRequestBody(LlmRequest request, ProviderExecutionPlan executionPlan) {
        switch (config.getType()) {
            case ANTHROPIC:
                return buildAnthropicRequestBody(request, executionPlan.isStreaming());
            case OLLAMA:
                return buildOllamaRequestBody(request, executionPlan.isStreaming());
            case OPENAI_COMPATIBLE:
            default:
                return buildOpenAiRequestBody(request, executionPlan);
        }
    }

    /**
     * Builds OpenAI-compatible request body.
     */
    private String buildOpenAiRequestBody(LlmRequest request, ProviderExecutionPlan executionPlan) {
        JsonObject body = new JsonObject();
        body.addProperty("model", resolveModelName(request)); //$NON-NLS-1$
        body.addProperty("max_tokens", request.getMaxTokens() > 0 ? request.getMaxTokens() : config.getMaxTokens()); //$NON-NLS-1$
        body.addProperty("stream", executionPlan.isStreaming()); //$NON-NLS-1$

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

        executionPlan.getRequestOverrides().entrySet()
                .forEach(entry -> body.add(entry.getKey(), entry.getValue().deepCopy()));

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
            msgObj.addProperty("content", limitOutboundToolResultContent(msg)); //$NON-NLS-1$
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

    private String limitOutboundToolResultContent(LlmMessage message) {
        String content = message.getContent();
        if (content == null || content.length() <= OUTBOUND_TOOL_RESULT_CHAR_LIMIT) {
            return content;
        }

        int headChars = Math.min(OUTBOUND_TOOL_RESULT_HEAD_CHARS, content.length());
        int remaining = Math.max(0, content.length() - headChars);
        int tailChars = Math.min(OUTBOUND_TOOL_RESULT_TAIL_CHARS, remaining);

        StringBuilder builder = new StringBuilder();
        builder.append("[tool result truncated by CodePilot1C]\n"); //$NON-NLS-1$
        builder.append("tool_call_id: ").append(message.getToolCallId()).append('\n'); //$NON-NLS-1$
        builder.append("original_length_chars: ").append(content.length()).append('\n'); //$NON-NLS-1$
        builder.append("included_head_chars: ").append(headChars).append('\n'); //$NON-NLS-1$
        builder.append("included_tail_chars: ").append(tailChars).append("\n\n"); //$NON-NLS-1$
        builder.append(content, 0, headChars);
        if (tailChars > 0) {
            builder.append("\n\n...[truncated middle]...\n\n"); //$NON-NLS-1$
            builder.append(content, content.length() - tailChars, content.length());
        }

        LOG.info("Truncated outbound tool result for %s: original=%d chars, outbound=%d chars", //$NON-NLS-1$
                message.getToolCallId(), content.length(), builder.length());
        return builder.toString();
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
        body.addProperty("model", resolveModelName(request)); //$NON-NLS-1$
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
        body.addProperty("model", resolveModelName(request)); //$NON-NLS-1$
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

    private String resolveModelName(LlmRequest request) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return config.getModel();
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
        JsonArray choices = getArray(json, "choices"); //$NON-NLS-1$
        if (choices == null || choices.size() == 0) {
            throw new LlmProviderException("No choices in response"); //$NON-NLS-1$
        }

        JsonObject choice = getObject(choices.get(0));
        if (choice == null) {
            throw new LlmProviderException("First choice is not an object"); //$NON-NLS-1$
        }
        JsonObject message = getObject(choice, "message"); //$NON-NLS-1$
        if (message == null) {
            throw new LlmProviderException("No message in response choice"); //$NON-NLS-1$
        }

        // Log message structure for debugging
        LOG.debug("Message keys: %s", message.keySet()); //$NON-NLS-1$

        // Handle content - may be null when tool_calls are present
        String content = null;
        if (message.has("content") && !message.get("content").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            content = message.get("content").getAsString(); //$NON-NLS-1$
        }

        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() ? //$NON-NLS-1$ //$NON-NLS-2$
                normalizeFinishReason(choice.get("finish_reason").getAsString()) : LlmResponse.FINISH_REASON_STOP; //$NON-NLS-1$

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
            if (!toolCalls.isEmpty() && !LlmResponse.FINISH_REASON_TOOL_USE.equals(finishReason)) { //$NON-NLS-1$
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
        if (toolCallsJson == null) {
            return toolCalls;
        }
        for (JsonElement element : toolCallsJson) {
            JsonObject callObj = getObject(element);
            if (callObj == null) {
                continue;
            }
            String id = getString(callObj, "id"); //$NON-NLS-1$
            JsonObject function = getObject(callObj, "function"); //$NON-NLS-1$
            if (id == null || function == null) {
                continue;
            }
            String name = getString(function, "name"); //$NON-NLS-1$
            String arguments = getString(function, "arguments"); //$NON-NLS-1$
            if (name == null || name.isBlank()) {
                continue;
            }
            toolCalls.add(new ToolCall(id, name, arguments != null ? arguments : "{}")); //$NON-NLS-1$
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
                JsonArray choices = getArray(json, "choices"); //$NON-NLS-1$
                if (choices != null && choices.size() > 0) {
                    JsonObject choice = getObject(choices.get(0));
                    JsonObject delta = getObject(choice, "delta"); //$NON-NLS-1$
                    String content = getString(delta, "content"); //$NON-NLS-1$
                    if (content != null) {
                        return content;
                    }
                }
                break;
        }
        return null;
    }

    private String extractReasoningChunk(JsonObject json) {
        switch (config.getType()) {
            case OPENAI_COMPATIBLE:
                JsonArray choices = getArray(json, "choices"); //$NON-NLS-1$
                if (choices == null || choices.size() == 0) {
                    return null;
                }
                JsonObject choice = getObject(choices.get(0));
                JsonObject delta = getObject(choice, "delta"); //$NON-NLS-1$
                String reasoning = getString(delta, "reasoning_content"); //$NON-NLS-1$
                if (reasoning != null) {
                    return reasoning;
                }
                return getString(delta, "reasoning"); //$NON-NLS-1$
            default:
                return null;
        }
    }

    private JsonArray getArray(JsonObject object, String propertyName) {
        if (object == null || propertyName == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(propertyName);
        return element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private JsonObject getObject(JsonObject object, String propertyName) {
        if (object == null || propertyName == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(propertyName);
        return getObject(element);
    }

    private JsonObject getObject(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private String getString(JsonObject object, String propertyName) {
        if (object == null || propertyName == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(propertyName);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
