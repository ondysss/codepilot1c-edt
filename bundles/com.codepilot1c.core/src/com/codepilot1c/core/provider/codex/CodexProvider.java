/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.provider.AbstractLlmProvider;
import com.codepilot1c.core.provider.LlmProviderException;
import com.codepilot1c.core.provider.LlmRequestCancellation;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.codepilot1c.core.provider.config.LlmProviderConfig;

/**
 * LLM provider for OpenAI Codex (ChatGPT subscription) via the Responses API.
 *
 * <p>Authenticates with OAuth credentials resolved through {@link CodexAuthProvider}
 * (Bearer access token + {@code chatgpt-account-id}) rather than a static API key, and speaks the
 * OpenAI Responses wire format ({@code POST .../codex/responses}, SSE) instead of chat
 * completions. The Codex backend streams responses, so {@link #complete} runs the streaming path
 * and accumulates the result.</p>
 */
public class CodexProvider extends AbstractLlmProvider {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CodexProvider.class);
    private static final long AUTH_HEADER_TIMEOUT_SECONDS = 30L;
    private static final String NOT_CONFIGURED =
        "OpenAI Codex не подключён. Нажмите «Подключить ChatGPT» в настройках провайдера."; //$NON-NLS-1$

    private final LlmProviderConfig config;
    private final CodexAuthProvider authProvider;
    private final CodexResponsesRequestBuilder requestBuilder = new CodexResponsesRequestBuilder();

    public CodexProvider(LlmProviderConfig config) {
        this.config = config;
        this.authProvider = new CodexAuthProvider();
    }

    @Override
    public String getId() {
        return config.getId();
    }

    @Override
    public String getDisplayName() {
        return config.getName() != null ? config.getName() : "OpenAI Codex"; //$NON-NLS-1$
    }

    @Override
    public boolean isConfigured() {
        return authProvider.isLoggedIn() && config.getModel() != null && !config.getModel().isBlank();
    }

    @Override
    public boolean supportsStreaming() {
        return config.isStreamingEnabled();
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        // The ChatGPT/Codex Responses backend accepts image input for multimodal models
        // (GPT-5.x, etc.). Without this override the provider inherits the text-only default
        // and the UI blocks image attachments even for vision-capable models.
        return ProviderCapabilities.builder()
                .imageInput(ProviderCapabilities.inferImageInputFromModel(config.getModel()))
                .documentInput(true)
                .attachmentMetadata(true)
                .streamUsage(true)
                .build();
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        return complete(request, new LlmRequestCancellation());
    }

    @Override
    public CompletableFuture<LlmResponse> complete(
            LlmRequest request, LlmRequestCancellation cancellation) {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(new LlmProviderException(NOT_CONFIGURED));
        }
        LlmRequestCancellation requestCancellation = beginRequest(cancellation);
        CompletableFuture<LlmResponse> future = CompletableFuture.supplyAsync(
                () -> collectResponse(request, requestCancellation));
        requestCancellation.onCancel(() -> future.cancel(true));
        return future.whenComplete((result, error) -> endRequest(requestCancellation));
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
        streamComplete(request, consumer, new LlmRequestCancellation());
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer,
            LlmRequestCancellation cancellation) {
        if (!isConfigured()) {
            throw new LlmProviderException(NOT_CONFIGURED);
        }
        LlmRequestCancellation requestCancellation = beginRequest(cancellation);
        try {
            executeStream(request, consumer, requestCancellation);
        } finally {
            endRequest(requestCancellation);
        }
    }

    private LlmResponse collectResponse(LlmRequest request, LlmRequestCancellation cancellation) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        String[] finishReason = { LlmResponse.FINISH_REASON_STOP };
        LlmResponse.Usage[] usage = { null };
        RuntimeException[] error = { null };

        Consumer<LlmStreamChunk> collector = chunk -> {
            if (chunk == null) {
                return;
            }
            if (chunk.isError()) {
                error[0] = new LlmProviderException(chunk.getErrorMessage());
                return;
            }
            if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                content.append(chunk.getContent());
            }
            if (chunk.hasReasoning()) {
                reasoning.append(chunk.getReasoningContent());
            }
            if (chunk.hasToolCalls()) {
                toolCalls.addAll(chunk.getToolCalls());
            }
            if (chunk.hasUsage()) {
                usage[0] = chunk.getUsage();
            }
            if (chunk.isComplete() && chunk.getFinishReason() != null) {
                finishReason[0] = chunk.getFinishReason();
            }
        };

        executeStream(request, collector, cancellation);
        if (error[0] != null) {
            throw error[0];
        }
        return new LlmResponse(
            content.toString(),
            resolveModel(request),
            config.getModel(),
            usage[0],
            finishReason[0],
            toolCalls.isEmpty() ? null : toolCalls,
            reasoning.length() > 0 ? reasoning.toString() : null);
    }

    private void executeStream(LlmRequest request, Consumer<LlmStreamChunk> consumer,
            LlmRequestCancellation cancellation) {
        String body = requestBuilder.build(request, config.getModel(), config.getMaxTokens(), true,
            config.getReasoningEffort());
        HttpRequest httpRequest = buildHttpRequest(body);
        CodexResponsesStreamParser parser = new CodexResponsesStreamParser(consumer);
        final LlmProviderException[] error = { null };

        sendAsyncStreaming(
            httpRequest,
            (line, complete) -> {
                if (!cancellation.isCancelled()) {
                    parser.process(line, complete);
                }
            },
            ex -> {
                error[0] = ex instanceof LlmProviderException
                    ? (LlmProviderException) ex
                    : new LlmProviderException("Codex stream request failed: " + ex.getMessage(), ex); //$NON-NLS-1$
                consumer.accept(LlmStreamChunk.error(error[0].getMessage()));
            },
            cancellation
        ).join();

        if (error[0] != null && !cancellation.isCancelled()) {
            throw error[0];
        }
    }

    private HttpRequest buildHttpRequest(String body) {
        String url = config.getChatEndpointUrl();
        if (url == null || url.isBlank()) {
            url = CodexOAuthConstants.CODEX_BASE_URL + "/responses"; //$NON-NLS-1$
        }
        Map<String, String> authHeaders = resolveAuthHeaders();
        if (authHeaders.isEmpty()) {
            throw new LlmProviderException(NOT_CONFIGURED);
        }
        HttpRequest.Builder builder = createPostRequest(url)
            .header("OpenAI-Beta", "responses=experimental") //$NON-NLS-1$ //$NON-NLS-2$
            .header("Accept", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$
        authHeaders.forEach(builder::header);
        return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private Map<String, String> resolveAuthHeaders() {
        try {
            Map<String, String> headers = authProvider.getAuthHeaders().get(AUTH_HEADER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return headers != null ? headers : Collections.emptyMap();
        } catch (Exception e) {
            LOG.warn("Failed to resolve Codex auth headers: %s", e.getMessage()); //$NON-NLS-1$
            return Collections.emptyMap();
        }
    }

    private String resolveModel(LlmRequest request) {
        return request.getModel() != null && !request.getModel().isBlank()
            ? request.getModel()
            : config.getModel();
    }
}
