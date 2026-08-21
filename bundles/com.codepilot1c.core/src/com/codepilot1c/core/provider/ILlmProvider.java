/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;

/**
 * Interface for LLM (Large Language Model) providers.
 *
 * <p>Implementations of this interface provide access to different LLM services
 * such as Claude, OpenAI, Ollama, etc.</p>
 */
public interface ILlmProvider {

    /**
     * Returns the unique identifier of this provider.
     *
     * @return the provider ID
     */
    String getId();

    /**
     * Returns the display name of this provider.
     *
     * @return the display name
     */
    String getDisplayName();

    /**
     * Checks if this provider is properly configured and ready to use.
     *
     * @return true if configured
     */
    boolean isConfigured();

    /**
     * Checks if this provider supports streaming responses.
     *
     * @return true if streaming is supported
     */
    boolean supportsStreaming();

    /**
     * Returns declared provider-specific runtime capabilities.
     *
     * @return provider capabilities, never {@code null}
     */
    default ProviderCapabilities getCapabilities() {
        return ProviderCapabilities.none();
    }

    /**
     * Sends a request and returns a future with the complete response.
     *
     * @param request the request to send
     * @return a future that will complete with the response
     * @throws LlmProviderException if the request fails
     */
    CompletableFuture<LlmResponse> complete(LlmRequest request);

    /**
     * Scoped compatibility overload. Legacy providers need no changes: their
     * returned future is cancelled without invoking provider-wide cancellation.
     */
    default CompletableFuture<LlmResponse> complete(
            LlmRequest request, LlmRequestCancellation cancellation) {
        if (cancellation == null) {
            return complete(request);
        }
        if (cancellation.isCancelled()) {
            return CompletableFuture.failedFuture(
                    new java.util.concurrent.CancellationException("Request cancelled")); //$NON-NLS-1$
        }
        CompletableFuture<LlmResponse> future = complete(request);
        cancellation.onCancel(() -> future.cancel(true));
        return future;
    }

    /**
     * Sends a request and streams the response in chunks.
     *
     * @param request  the request to send
     * @param consumer the consumer for response chunks
     * @throws LlmProviderException if the request fails
     */
    void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer);

    /** Scoped streaming compatibility overload that suppresses only this request's callbacks. */
    default void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer,
            LlmRequestCancellation cancellation) {
        if (cancellation == null) {
            streamComplete(request, consumer);
            return;
        }
        if (cancellation.isCancelled()) {
            return;
        }
        streamComplete(request, chunk -> {
            if (!cancellation.isCancelled()) {
                consumer.accept(chunk);
            }
        });
    }

    /**
     * Cancels any ongoing requests.
     */
    void cancel();

    /**
     * Disposes of any resources held by this provider.
     */
    void dispose();
}
