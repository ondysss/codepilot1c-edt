/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmConversationSanitizer;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderException;
import com.codepilot1c.core.provider.LlmRequestCancellation;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.codepilot1c.core.provider.ProviderUtils;
import com.codepilot1c.core.provider.codex.CodexProvider;
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
    private static final String HEADER_CODEPILOT_PROMPT_CACHE = "X-CodePilot-Prompt-Cache"; //$NON-NLS-1$
    private static final int OUTBOUND_TOOL_RESULT_CHAR_LIMIT = 50_000;
    private static final int OUTBOUND_TOOL_RESULT_HEAD_CHARS = 30_000;
    private static final int OUTBOUND_TOOL_RESULT_TAIL_CHARS = 15_000;
    private static final int LARGE_REQUEST_TIMEOUT_THRESHOLD_CHARS = 80_000;
    private static final int VERY_LARGE_REQUEST_TIMEOUT_THRESHOLD_CHARS = 200_000;
    private static final int LARGE_REQUEST_TIMEOUT_SECONDS = 120;
    private static final int VERY_LARGE_REQUEST_TIMEOUT_SECONDS = 180;

    private final LlmProviderConfig config;
    private final OpenAiModelCompatibilityPolicy openAiCompatibilityPolicy;
    private final ProviderHttpTransport httpTransport;
    private final Gson gson;
    private final ILlmProvider codexDelegate;
    private final Function<LlmProviderConfig, String> apiKeyResolver;
    private final IntSupplier requestTimeoutSupplier;
    private final ConcurrentHashMap<LlmRequestCancellation, AtomicInteger> activeRequests =
            new ConcurrentHashMap<>();

    /**
     * Creates a new dynamic provider with the given configuration.
     */
    public DynamicLlmProvider(LlmProviderConfig config) {
        this(config, LlmProviderConfigStore::resolveApiKey);
    }

    DynamicLlmProvider(LlmProviderConfig config, Function<LlmProviderConfig, String> apiKeyResolver) {
        this(config, apiKeyResolver, null);
    }

    DynamicLlmProvider(LlmProviderConfig config, Function<LlmProviderConfig, String> apiKeyResolver,
            IntSupplier requestTimeoutSupplier) {
        this.config = config;
        this.apiKeyResolver = apiKeyResolver;
        this.requestTimeoutSupplier = requestTimeoutSupplier != null
                ? requestTimeoutSupplier
                : this::loadRequestTimeoutSeconds;
        this.openAiCompatibilityPolicy = new OpenAiModelCompatibilityPolicy();
        this.gson = new Gson();

        HttpClient client = HttpClient.newBuilder()
                // vLLM/uvicorn deployments are commonly exposed over plain HTTP and can fail
                // with Java HttpClient HTTP/2 (h2c) by not parsing the request body.
                // HTTP/1.1 is the most compatible default for OpenAI-compatible endpoints.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.httpTransport = new ProviderHttpTransport(client);

        // OPENAI_CODEX uses the Responses API (different wire format and OAuth headers); route it
        // to a dedicated provider instead of the chat/completions path implemented below.
        this.codexDelegate = config.getType() == ProviderType.OPENAI_CODEX
                ? new CodexProvider(config)
                : null;
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
        if (codexDelegate != null) {
            return codexDelegate.isConfigured();
        }
        LlmProviderConfig resolvedConfig = config.copy();
        resolvedConfig.setApiKey(apiKeyResolver.apply(config));
        return resolvedConfig.isConfigured();
    }

    @Override
    public boolean supportsStreaming() {
        if (codexDelegate != null) {
            return codexDelegate.supportsStreaming();
        }
        return config.isStreamingEnabled();
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        if (codexDelegate != null) {
            return codexDelegate.getCapabilities();
        }
        return ProviderUtils.capabilitiesFor(config);
    }

    /**
     * Returns the underlying configuration.
     */
    public LlmProviderConfig getConfig() {
        return config;
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        return complete(request, new LlmRequestCancellation());
    }

    @Override
    public CompletableFuture<LlmResponse> complete(
            LlmRequest request, LlmRequestCancellation cancellation) {
        if (codexDelegate != null) {
            return codexDelegate.complete(request, cancellation);
        }
        long startTime = System.currentTimeMillis();
        String correlationId = LogSanitizer.newCorrelationId();

        if (!isConfigured()) {
            LOG.warn("[%s] Provider is not configured", correlationId); //$NON-NLS-1$
            return CompletableFuture.failedFuture(
                    new LlmProviderException("Provider is not configured")); //$NON-NLS-1$
        }

        LlmRequestCancellation requestCancellation = beginRequest(cancellation);

        LOG.info("[%s] DynamicProvider complete: messages=%d", //$NON-NLS-1$
                correlationId, request.getMessages().size());

        ProviderExecutionPlan executionPlan = buildExecutionPlan(request, false);
        return completeWithPlan(request, executionPlan, startTime, correlationId, requestCancellation)
                .whenComplete((result, error) -> endRequest(requestCancellation));
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
        streamComplete(request, consumer, new LlmRequestCancellation());
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer,
            LlmRequestCancellation cancellation) {
        if (codexDelegate != null) {
            codexDelegate.streamComplete(request, consumer, cancellation);
            return;
        }
        long startTime = System.currentTimeMillis();
        String correlationId = LogSanitizer.newCorrelationId();

        if (!isConfigured()) {
            LOG.warn("[%s] Provider is not configured for streaming", correlationId); //$NON-NLS-1$
            throw new LlmProviderException("Provider is not configured"); //$NON-NLS-1$
        }

        LlmRequestCancellation requestCancellation = beginRequest(cancellation);
        OpenAiStreamingToolCallParser streamingToolCallParser = new OpenAiStreamingToolCallParser();
        ProviderExecutionPlan executionPlan = buildExecutionPlan(request, true);
        // CODEPILOT_BACKEND is wire-compatible with OpenAI — use OpenAiStreamingSession for both
        boolean useOpenAiStreaming = config.getType() == ProviderType.OPENAI_COMPATIBLE
                || config.getType() == ProviderType.CODEPILOT_BACKEND;
        OpenAiStreamingSession openAiSession = useOpenAiStreaming
                ? new OpenAiStreamingSession(
                        correlationId,
                        request.hasTools(),
                        streamingToolCallParser,
                        getCapabilities().supportsTextToolCallFallback() && request.hasTools())
                : null;
        ProviderStreamProcessingSummary summary = openAiSession != null
                ? openAiSession.getSummary()
                : new ProviderStreamProcessingSummary(correlationId, request.hasTools());

        LOG.info("[%s] DynamicProvider streamComplete: messages=%d", //$NON-NLS-1$
                correlationId, request.getMessages().size());
        if (!executionPlan.isStreaming()) {
            LOG.info("[%s] Using non-stream execution plan for streaming request", correlationId); //$NON-NLS-1$
            try {
                replayResponseAsStream(request, consumer, correlationId, executionPlan, requestCancellation);
            } finally {
                endRequest(requestCancellation);
            }
            return;
        }

        String requestBody = buildRequestBody(request, executionPlan);
        HttpRequest httpRequest = buildHttpRequest(requestBody);

        AtomicReference<java.util.stream.Stream<String>> responseBody = new AtomicReference<>();
        try {
            CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> exchange =
                    httpTransport.sendStreamingLinesAsync(httpRequest);
            requestCancellation.onCancel(() -> {
                exchange.cancel(true);
                java.util.stream.Stream<String> body = responseBody.get();
                if (body != null) {
                    body.close();
                }
            });
            HttpResponse<java.util.stream.Stream<String>> response = exchange.join();
            responseBody.set(response.body());

            if (requestCancellation.isCancelled()) {
                LOG.debug("[%s] Stream cancelled before processing", correlationId); //$NON-NLS-1$
                return;
            }

            if (response.statusCode() != 200) {
                response.body().close();
                LOG.error("[%s] Stream API error: status=%d", correlationId, response.statusCode()); //$NON-NLS-1$
                throw new LlmProviderException("Provider returned an HTTP error", null, //$NON-NLS-1$
                        response.statusCode(), null);
            }

            // Track finish reason from stream
            final String[] streamFinishReason = { LlmResponse.FINISH_REASON_STOP };

            // Process lines as they arrive
            response.body().forEach(line -> {
                if (requestCancellation.isCancelled()) {
                    return;
                }
                String finishReason = processStreamLine(line, consumer, summary, openAiSession);
                if (finishReason != null) {
                    streamFinishReason[0] = finishReason;
                }
            });

            if (!requestCancellation.isCancelled() && openAiSession != null) {
                String completionFinishReason = openAiSession.completePendingToolCalls(consumer);
                if (completionFinishReason != null) {
                    streamFinishReason[0] = completionFinishReason;
                }
            }

            if (!requestCancellation.isCancelled() && summary.hasTerminalError()) {
                logStreamSummary(summary, openAiSession);
                LOG.warn("[%s] Structured stream error", correlationId); //$NON-NLS-1$
                return;
            }

            if (!requestCancellation.isCancelled() && summary.shouldFallbackToNonStreaming()) {
                if (summary.isReasoningOnlyResponse()) {
                    LOG.warn("[%s] Reasoning-only response detected (reasoning=%d, content=0, toolCalls=0) — retrying as non-streaming", //$NON-NLS-1$
                            correlationId, summary.getReasoningChunks().get());
                } else {
                    LOG.warn("[%s] Falling back to non-streaming response handling: parseFailures=%d, opaqueChunks=%d", //$NON-NLS-1$
                            correlationId, summary.getParseFailures().get(), summary.getOpaqueChunks().get());
                }
                streamingToolCallParser.clear();
                replayNonStreamingFallback(request, consumer, correlationId, requestCancellation);
                return;
            }

            // Send final chunk if not already done
            if (!requestCancellation.isCancelled()) {
                consumer.accept(LlmStreamChunk.complete(normalizeFinishReason(streamFinishReason[0])));
            }

            long duration = System.currentTimeMillis() - startTime;
            logStreamSummary(summary, openAiSession);
            LOG.info("[%s] DynamicProvider stream completed in %s", correlationId, LogSanitizer.formatDuration(duration)); //$NON-NLS-1$

        } catch (CompletionException | CancellationException | UncheckedIOException e) {
            long duration = System.currentTimeMillis() - startTime;
            if (!requestCancellation.isCancelled()) {
                LOG.error("[%s] DynamicProvider stream failed after %s", //$NON-NLS-1$
                        correlationId, LogSanitizer.formatDuration(duration));
                throw new LlmProviderException("Stream request failed", e); //$NON-NLS-1$
            }
        } finally {
            java.util.stream.Stream<String> body = responseBody.get();
            if (body != null) {
                body.close();
            }
            endRequest(requestCancellation);
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
                // CODEPILOT_BACKEND is wire-compatible with OpenAI — route through same session
                if ((config.getType() == ProviderType.OPENAI_COMPATIBLE
                        || config.getType() == ProviderType.CODEPILOT_BACKEND) && openAiSession != null) {
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
                LOG.debug("[%s] Failed to parse stream chunk", summary.getCorrelationId()); //$NON-NLS-1$
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

    private void replayNonStreamingFallback(LlmRequest request, Consumer<LlmStreamChunk> consumer,
            String correlationId, LlmRequestCancellation cancellation) {
        replayResponseAsStream(buildNonStreamingFallbackRequest(request), consumer, correlationId,
                buildExecutionPlan(buildNonStreamingFallbackRequest(request), false), cancellation);
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

    private LlmRequestCancellation beginRequest(LlmRequestCancellation cancellation) {
        LlmRequestCancellation effective = cancellation != null
                ? cancellation : new LlmRequestCancellation();
        activeRequests.compute(effective, (ignored, count) -> {
            if (count == null) {
                return new AtomicInteger(1);
            }
            count.incrementAndGet();
            return count;
        });
        return effective;
    }

    private void endRequest(LlmRequestCancellation cancellation) {
        activeRequests.computeIfPresent(cancellation, (ignored, count) ->
                count.decrementAndGet() <= 0 ? null : count);
    }

    @Override
    public void cancel() {
        if (codexDelegate != null) {
            codexDelegate.cancel();
            return;
        }
        activeRequests.keySet().forEach(LlmRequestCancellation::cancel);
    }

    @Override
    public void dispose() {
        if (codexDelegate != null) {
            codexDelegate.dispose();
            return;
        }
        cancel();
    }

    /**
     * Builds the HTTP request with appropriate headers.
     */
    private HttpRequest buildHttpRequest(String body) {
        String url = config.getChatEndpointUrl();
        int timeoutSeconds = resolveRequestTimeoutSeconds(body);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$

        // Add authorization header based on provider type
        String apiKey = apiKeyResolver.apply(config);
        boolean authenticated = config.getType().requiresStaticApiKey()
                && apiKey != null && !apiKey.isEmpty();
        if (authenticated) {
            switch (config.getType()) {
                case ANTHROPIC:
                    builder.header("x-api-key", apiKey); //$NON-NLS-1$
                    builder.header("anthropic-version", "2023-06-01"); //$NON-NLS-1$ //$NON-NLS-2$
                    break;
                case OPENAI_COMPATIBLE:
                case CODEPILOT_BACKEND:
                default:
                    builder.header("Authorization", "Bearer " + apiKey); //$NON-NLS-1$ //$NON-NLS-2$
                    break;
            }
        }

        if (ProviderUtils.supportsPromptCacheHeaders(config)) {
            builder.header(HEADER_CODEPILOT_PROMPT_CACHE, "prefer"); //$NON-NLS-1$
        }

        // Add custom headers
        config.getCustomHeaders().forEach((key, value) -> {
            builder.header(key, value);
        });

        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        LOG.debug("HTTP request built: bodyChars=%d, timeoutSeconds=%d, authenticated=%b, customHeaders=%d", //$NON-NLS-1$
                body != null ? body.length() : 0, timeoutSeconds, authenticated,
                config.getCustomHeaders().size());
        return builder.build();
    }

    private int getRequestTimeoutSeconds() {
        return requestTimeoutSupplier.getAsInt();
    }

    private int loadRequestTimeoutSeconds() {
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
            ProviderExecutionPlan executionPlan, long startTime, String correlationId,
            LlmRequestCancellation cancellation) {
        if (executionPlan.getReason() != null) {
            LOG.debug("[%s] Provider execution plan: stream=%b", correlationId, //$NON-NLS-1$
                    executionPlan.isStreaming());
        }

        String requestBody = buildRequestBody(request, executionPlan);
        LOG.debug("[%s] Request body size: chars=%d", correlationId, requestBody.length()); //$NON-NLS-1$

        HttpRequest httpRequest = buildHttpRequest(requestBody);

        CompletableFuture<HttpResponse<String>> exchange = httpTransport.sendStringAsync(httpRequest);
        cancellation.onCancel(() -> exchange.cancel(true));
        CompletableFuture<LlmResponse> responseFuture = exchange
                .thenApply(response -> {
                    if (cancellation.isCancelled()) {
                        throw new CancellationException("Request cancelled"); //$NON-NLS-1$
                    }
                    long duration = System.currentTimeMillis() - startTime;
                    LOG.debug("[%s] Response status: %d in %s", correlationId, response.statusCode(), //$NON-NLS-1$
                            LogSanitizer.formatDuration(duration));
                    LOG.debug("[%s] Response body size: chars=%d", correlationId, //$NON-NLS-1$
                            response.body() != null ? response.body().length() : 0);
                    return parseResponse(response, request);
                })
                .whenComplete((result, error) -> {
                    long duration = System.currentTimeMillis() - startTime;
                    if (error != null) {
                        LOG.error("[%s] DynamicProvider request failed after %s", //$NON-NLS-1$
                                correlationId, LogSanitizer.formatDuration(duration));
                    } else {
                        LOG.info("[%s] DynamicProvider response received in %s", //$NON-NLS-1$
                                correlationId, LogSanitizer.formatDuration(duration));
                    }
                });

        cancellation.onCancel(() -> responseFuture.cancel(true));
        return responseFuture;
    }

    private ProviderExecutionPlan buildExecutionPlan(LlmRequest request, boolean requestedStreaming) {
        // Both OPENAI_COMPATIBLE and CODEPILOT_BACKEND use the same compatibility policy
        // since CODEPILOT_BACKEND is wire-compatible with OpenAI
        if (config.getType() == ProviderType.OPENAI_COMPATIBLE
                || config.getType() == ProviderType.CODEPILOT_BACKEND) {
            return openAiCompatibilityPolicy.plan(config, request, requestedStreaming);
        }
        return ProviderExecutionPlan.streaming(requestedStreaming && config.isStreamingEnabled());
    }

    private void replayResponseAsStream(LlmRequest request, Consumer<LlmStreamChunk> consumer,
            String correlationId, ProviderExecutionPlan executionPlan,
            LlmRequestCancellation cancellation) {
        if (cancellation.isCancelled()) {
            return;
        }

        long fallbackStartTime = System.currentTimeMillis();
        LlmResponse response = completeWithPlan(request, executionPlan, fallbackStartTime,
                correlationId + "-replay", cancellation).join(); //$NON-NLS-1$

        if (cancellation.isCancelled()) {
            return;
        }

        LOG.info("[%s] Non-stream replay completed: hasContent=%b, toolCalls=%d", //$NON-NLS-1$
                correlationId,
                response.getContent() != null && !response.getContent().isEmpty(),
                response.getToolCalls().size());

        if (response.hasReasoningField() && !cancellation.isCancelled()) {
            consumer.accept(LlmStreamChunk.reasoning(response.getReasoningContent()));
        }
        if (response.getContent() != null && !response.getContent().isEmpty() && !cancellation.isCancelled()) {
            consumer.accept(LlmStreamChunk.content(response.getContent()));
        }
        if (response.hasToolCalls() && !cancellation.isCancelled()) {
            consumer.accept(LlmStreamChunk.toolCalls(response.getToolCalls()));
        }
        if (response.getUsage() != null && !cancellation.isCancelled()) {
            consumer.accept(LlmStreamChunk.usage(response.getUsage()));
        }
        if (!cancellation.isCancelled()) {
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
            case CODEPILOT_BACKEND:
                return buildOpenAiRequestBody(request, executionPlan);
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
        body.addProperty(executionPlan.getMaxTokensParameterName(),
                request.getMaxTokens() > 0 ? request.getMaxTokens() : config.getMaxTokens());
        body.addProperty("stream", executionPlan.isStreaming()); //$NON-NLS-1$
        ProviderCapabilities caps = getCapabilities();

        // Request real token usage from streaming providers that support it
        // (Plan 2.3). Only emit when both streaming AND capability are on —
        // generic OpenAI gateways may reject unknown fields.
        if (executionPlan.isStreaming() && caps.supportsStreamUsage()) {
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true); //$NON-NLS-1$
            body.add("stream_options", streamOptions); //$NON-NLS-1$
        }

        JsonArray messages = new JsonArray();

        // Add all messages (including system messages and tool results)
        List<LlmMessage> sanitizedMessages = LlmConversationSanitizer
                .sanitizeForOpenAiToolCalls(request.getMessages());
        for (LlmMessage msg : sanitizedMessages) {
            messages.add(serializeMessage(msg, caps));
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
    private JsonObject serializeMessage(LlmMessage msg, ProviderCapabilities caps) {
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

            // Moonshot/Kimi API requires reasoning_content to be preserved in assistant messages
            // with tool calls. Without it, follow-up responses degrade to reasoning-only (contentChunks=0).
            // See: https://github.com/BerriAI/litellm/issues/21672
            if (msg.hasReasoningContentField()) {
                msgObj.addProperty("reasoning_content", msg.getReasoningContent()); //$NON-NLS-1$
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
            JsonElement content = ProviderMessageContentSerializer.toOpenAiContent(msg, caps);
            msgObj.add("content", content); //$NON-NLS-1$
            if (msg.getRole() == LlmMessage.Role.ASSISTANT && msg.hasReasoningContentField()) {
                msgObj.addProperty("reasoning_content", msg.getReasoningContent()); //$NON-NLS-1$
            }
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

        LOG.info("Truncated outbound tool result: original=%d chars, outbound=%d chars", //$NON-NLS-1$
                content.length(), builder.length());
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
        ProviderCapabilities caps = getCapabilities();

        // Extract system message (Anthropic uses separate "system" field)
        JsonArray messages = new JsonArray();
        for (LlmMessage msg : request.getMessages()) {
            if (msg.getRole() == LlmMessage.Role.SYSTEM) {
                // System message is a separate field in Anthropic API
                body.addProperty("system", msg.getContent()); //$NON-NLS-1$
            } else {
                messages.add(serializeAnthropicMessage(msg, caps));
            }
        }

        body.add("messages", messages); //$NON-NLS-1$
        return gson.toJson(body);
    }

    private JsonObject serializeAnthropicMessage(LlmMessage msg, ProviderCapabilities caps) {
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("role", msg.getRole().getValue()); //$NON-NLS-1$

        if (msg.getRole() == LlmMessage.Role.TOOL) {
            JsonArray contentArray = new JsonArray();
            JsonObject toolResult = new JsonObject();
            toolResult.addProperty("type", "tool_result"); //$NON-NLS-1$ //$NON-NLS-2$
            toolResult.addProperty("tool_use_id", msg.getToolCallId()); //$NON-NLS-1$
            toolResult.addProperty("content", msg.getContent()); //$NON-NLS-1$
            contentArray.add(toolResult);
            msgObj.add("content", contentArray); //$NON-NLS-1$
            msgObj.addProperty("role", "user"); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (msg.hasToolCalls()) {
            JsonArray contentArray = new JsonArray();
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                JsonObject textBlock = new JsonObject();
                textBlock.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
                textBlock.addProperty("text", msg.getContent()); //$NON-NLS-1$
                contentArray.add(textBlock);
            }
            for (ToolCall call : msg.getToolCalls()) {
                JsonObject toolUse = new JsonObject();
                toolUse.addProperty("type", "tool_use"); //$NON-NLS-1$ //$NON-NLS-2$
                toolUse.addProperty("id", call.getId()); //$NON-NLS-1$
                toolUse.addProperty("name", call.getName()); //$NON-NLS-1$
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
            msgObj.add("content", ProviderMessageContentSerializer.toAnthropicContent(msg, caps)); //$NON-NLS-1$
        }
        return msgObj;
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
            msgObj.addProperty("content", msg.hasContentParts()
                    ? msg.getTextualContentFallback()
                    : msg.getContent() != null ? msg.getContent() : ""); //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray images = buildOllamaImages(msg);
            if (images.size() > 0) {
                msgObj.add("images", images); //$NON-NLS-1$
            }
            messages.add(msgObj);
        }

        body.add("messages", messages); //$NON-NLS-1$
        return gson.toJson(body);
    }

    private JsonArray buildOllamaImages(LlmMessage message) {
        JsonArray images = new JsonArray();
        for (LlmAttachment attachment : message.getAttachments()) {
            if (!attachment.isImage()) {
                continue;
            }
            String encoded = encodeAttachmentBase64(attachment);
            if (encoded != null) {
                images.add(encoded);
            }
        }
        return images;
    }

    private String encodeAttachmentBase64(LlmAttachment attachment) {
        String effectivePath = attachment.getEffectivePath();
        if (effectivePath == null || effectivePath.isBlank()) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(effectivePath)));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to read Ollama image attachment"); //$NON-NLS-1$
            return null;
        }
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
    private LlmResponse parseResponse(HttpResponse<String> response, LlmRequest request) {
        if (response.statusCode() != 200) {
            throw new LlmProviderException("Provider returned an HTTP error", null, //$NON-NLS-1$
                    response.statusCode(), null);
        }

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            switch (config.getType()) {
                case ANTHROPIC:
                    return parseAnthropicResponse(json, request);
                case OLLAMA:
                    return parseOllamaResponse(json, request);
                case OPENAI_COMPATIBLE:
                default:
                    return parseOpenAiResponse(json, request);
            }
        } catch (Exception ignored) {
            throw new LlmProviderException("Failed to parse provider response"); //$NON-NLS-1$
        }
    }

    /**
     * Parses OpenAI-compatible response.
     */
    private LlmResponse parseOpenAiResponse(JsonObject json, LlmRequest request) {
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
        LOG.debug("Response message field count: %d", message.keySet().size()); //$NON-NLS-1$

        // Handle content - may be null when tool_calls are present
        String content = null;
        if (message.has("content") && !message.get("content").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            content = message.get("content").getAsString(); //$NON-NLS-1$
        }

        // Preserve reasoning_content for Kimi/Moonshot models.
        // This is critical for multi-turn tool usage: without reasoning_content
        // in assistant messages, follow-up responses degrade to reasoning-only.
        String reasoningContent = null;
        if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            reasoningContent = message.get("reasoning_content").getAsString(); //$NON-NLS-1$
        }

        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() ? //$NON-NLS-1$ //$NON-NLS-2$
                normalizeFinishReason(choice.get("finish_reason").getAsString()) : LlmResponse.FINISH_REASON_STOP; //$NON-NLS-1$
        String responseModel = getString(json, "model"); //$NON-NLS-1$
        LlmResponse.Usage usage = parseUsage(json);

        // Parse tool calls if present
        List<ToolCall> toolCalls = null;
        if (message.has("tool_calls")) { //$NON-NLS-1$
            LOG.debug("tool_calls found in message"); //$NON-NLS-1$
            JsonArray toolCallsJson = message.getAsJsonArray("tool_calls"); //$NON-NLS-1$
            toolCalls = parseToolCalls(toolCallsJson);

            LOG.debug("Parsed %d tool calls", toolCalls.size()); //$NON-NLS-1$
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
                    && reasoningContent != null && !reasoningContent.isEmpty()) {
                LOG.debug("Using reasoning_content as content fallback (provider returned empty content)"); //$NON-NLS-1$
                content = reasoningContent;
            }
        }

        // Content tool call fallback: if no structured tool_calls were found but content
        // or reasoning_content contains text-form tool call markers,
        // extract them. This is a safety net for when the model emits tool calls as text
        // instead of using the structured API.
        // Check both content and reasoning_content — kimi-k2.5 may place tool calls in reasoning.
        String contentToCheck = content;
        if ((contentToCheck == null || contentToCheck.isEmpty()) && reasoningContent != null) {
            contentToCheck = reasoningContent;
        }
        if ((toolCalls == null || toolCalls.isEmpty())
                && getCapabilities().supportsTextToolCallFallback()
                && ContentToolCallFallbackParser.hasToolCallMarkers(contentToCheck)) {
            List<ToolCall> fallbackCalls = ContentToolCallFallbackParser.extractFromContent(contentToCheck);
            if (!fallbackCalls.isEmpty()) {
                LOG.info("Content fallback: extracted %d tool call(s)", fallbackCalls.size()); //$NON-NLS-1$
                toolCalls = fallbackCalls;
                finishReason = LlmResponse.FINISH_REASON_TOOL_USE;
                // Strip tool call blocks from whichever field contained them
                if (contentToCheck == content) {
                    content = ContentToolCallFallbackParser.stripToolCallBlocks(content);
                }
                // Don't strip reasoning_content — it should be preserved as-is for history
            }
        }

        LOG.debug("Response parsed: hasContent=%b, hasReasoning=%b, toolCalls=%d", //$NON-NLS-1$
                content != null && !content.isEmpty(),
                reasoningContent != null && !reasoningContent.isEmpty(),
                toolCalls != null ? toolCalls.size() : 0);

        return new LlmResponse(content, resolveRequestedModel(request), responseModel,
                usage, finishReason, toolCalls, reasoningContent);
    }

    private String resolveRequestedModel(LlmRequest request) {
        if (request != null && request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return config.getModel();
    }

    private LlmResponse.Usage parseUsage(JsonObject json) {
        JsonObject usageJson = getObject(json, "usage"); //$NON-NLS-1$
        if (usageJson == null) {
            return null;
        }
        // Delegate to the shared parser so cache_read_input_tokens / cache_creation_input_tokens
        // normalization is identical across the streaming and non-streaming paths.
        return OpenAiUsageParser.parse(usageJson);
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
            if (name == null || name.isBlank()) {
                continue;
            }
            Optional<ToolCallArguments.Normalized> arguments =
                    ToolCallArguments.normalizeWithStatus(function.get("arguments")); //$NON-NLS-1$
            if (arguments.isEmpty()) {
                LOG.warn("Dropping tool call because arguments are not a JSON object"); //$NON-NLS-1$
                continue;
            }
            if (arguments.get().repaired()) {
                LOG.warn("Tool call arguments repaired from malformed payload"); //$NON-NLS-1$
            }
            toolCalls.add(new ToolCall(id, name, arguments.get().json(), arguments.get().repaired()));
        }
        return toolCalls;
    }

    /**
     * Parses Anthropic response.
     */
    private LlmResponse parseAnthropicResponse(JsonObject json, LlmRequest request) {
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

        return new LlmResponse(sb.toString(), resolveRequestedModel(request),
                config.getModel(), null, stopReason, null);
    }

    /**
     * Parses Ollama response.
     */
    private LlmResponse parseOllamaResponse(JsonObject json, LlmRequest request) {
        JsonObject message = json.getAsJsonObject("message"); //$NON-NLS-1$
        String content = message.get("content").getAsString(); //$NON-NLS-1$

        boolean done = json.has("done") && json.get("done").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        return new LlmResponse(content, resolveRequestedModel(request), config.getModel(), null,
                done ? "stop" : "length", null); //$NON-NLS-1$ //$NON-NLS-2$
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

    private int getInt(JsonObject object, String propertyName, int defaultValue) {
        if (object == null || propertyName == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return defaultValue;
        }
        JsonElement element = object.get(propertyName);
        return element.isJsonPrimitive() ? element.getAsInt() : defaultValue;
    }
}
