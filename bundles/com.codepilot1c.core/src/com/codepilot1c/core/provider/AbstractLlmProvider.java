/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.http.HttpClientConfig;
import com.codepilot1c.core.http.HttpClientFactory;
import com.codepilot1c.core.http.HttpRequestBodies;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Abstract base class for LLM providers.
 */
public abstract class AbstractLlmProvider implements ILlmProvider {

    protected final Gson gson = new GsonBuilder().create();
    protected final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Returns the preferences node for this plugin.
     *
     * @return the preferences
     */
    protected IEclipsePreferences getPreferences() {
        return InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
    }

    /**
     * Returns the configured request timeout in seconds.
     *
     * @return the timeout
     */
    protected int getRequestTimeout() {
        return getPreferences().getInt(VibePreferenceConstants.PREF_REQUEST_TIMEOUT, 60);
    }

    /**
     * Returns the shared HTTP client from the factory.
     *
     * <p>Uses the centralized {@link HttpClientFactory} which provides:
     * <ul>
     *   <li>HTTP/2 support with automatic fallback</li>
     *   <li>System proxy support</li>
     *   <li>Configurable redirects</li>
     * </ul>
     *
     * @return the HTTP client
     */
    protected HttpClient getHttpClient() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin != null) {
            HttpClientFactory factory = plugin.getHttpClientFactory();
            if (factory != null) {
                return factory.getSharedClient();
            }
        }
        // Fallback if plugin not started (e.g., testing)
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Returns the HTTP client configuration.
     *
     * @return the config, or null if not available
     */
    protected HttpClientConfig getHttpClientConfig() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin != null) {
            HttpClientFactory factory = plugin.getHttpClientFactory();
            return factory != null ? factory.getConfig() : null;
        }
        return null;
    }

    /**
     * Creates a POST request builder for the given URL.
     *
     * @param url the request URL
     * @return a request builder
     */
    protected HttpRequest.Builder createPostRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(getRequestTimeout()))
                .header("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Creates a POST request with JSON body and optional GZIP compression.
     *
     * <p>Uses {@link HttpRequestBodies#postJson} to apply GZIP compression
     * when the request body exceeds the configured threshold.</p>
     *
     * @param url the request URL
     * @param json the JSON body
     * @return the configured request builder
     */
    protected HttpRequest.Builder createPostRequestWithBody(String url, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(getRequestTimeout()));

        HttpClientConfig config = getHttpClientConfig();
        if (config != null) {
            return HttpRequestBodies.postJson(builder, json, config);
        }
        // Fallback without GZIP
        return HttpRequestBodies.postJsonUncompressed(builder, json);
    }

    /**
     * Sends an HTTP request asynchronously.
     *
     * @param request the request to send
     * @return a future with the response
     */
    protected CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
        return getHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends an HTTP request asynchronously with streaming response.
     * Lines are processed as they arrive without blocking.
     *
     * @param request the HTTP request
     * @param lineProcessor processor for each SSE line; receives line and completion callback
     * @param errorHandler handler for errors
     * @return a future that completes when streaming is done
     */
    protected CompletableFuture<Void> sendAsyncStreaming(
            HttpRequest request,
            BiConsumer<String, Runnable> lineProcessor,
            java.util.function.Consumer<Throwable> errorHandler) {

        return getHttpClient()
                .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAcceptAsync(response -> {
                    if (!isSuccess(response.statusCode())) {
                        // Read error body and parse structured error
                        try (InputStream is = response.body()) {
                            String errorBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            errorHandler.accept(parseStreamingError(response.statusCode(), errorBody));
                        } catch (IOException e) {
                            errorHandler.accept(new LlmProviderException(
                                    "HTTP error: " + response.statusCode(), e, response.statusCode(), null)); //$NON-NLS-1$
                        }
                        return;
                    }

                    // Process streaming response
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        String line;
                        final boolean[] completed = { false };
                        Runnable completeCallback = () -> completed[0] = true;

                        while (!completed[0] && !isCancelled() && (line = reader.readLine()) != null) {
                            lineProcessor.accept(line, completeCallback);
                        }
                    } catch (IOException e) {
                        if (!isCancelled()) {
                            errorHandler.accept(new LlmProviderException(
                                    "Failed to read stream response", e)); //$NON-NLS-1$
                        }
                    }
                })
                .exceptionally(ex -> {
                    if (!isCancelled()) {
                        errorHandler.accept(ex);
                    }
                    return null;
                });
    }

    /**
     * Checks if a response indicates success.
     *
     * @param statusCode the HTTP status code
     * @return true if successful
     */
    protected boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Parses a structured error from a streaming HTTP error response body.
     * Extracts error code, type, message and rate-limit details if present.
     */
    protected LlmProviderException parseStreamingError(int statusCode, String errorBody) {
        try {
            JsonObject json = JsonParser.parseString(errorBody).getAsJsonObject();
            if (json.has("error")) { //$NON-NLS-1$
                JsonObject error = json.getAsJsonObject("error"); //$NON-NLS-1$
                String type = error.has("type") ? error.get("type").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
                String code = error.has("code") ? error.get("code").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
                String message = error.has("message") ? error.get("message").getAsString() //$NON-NLS-1$ //$NON-NLS-2$
                        : "API error: " + statusCode; //$NON-NLS-1$

                LlmProviderException ex = new LlmProviderException(message, null, statusCode, type, code);

                // Parse rate-limit details if present
                if (json.has("details")) { //$NON-NLS-1$
                    try {
                        JsonObject details = json.getAsJsonObject("details"); //$NON-NLS-1$
                        long limitCents = details.has("limit_cents") ? details.get("limit_cents").getAsLong() : 0; //$NON-NLS-1$ //$NON-NLS-2$
                        long usedCents = details.has("used_cents") ? details.get("used_cents").getAsLong() : 0; //$NON-NLS-1$ //$NON-NLS-2$
                        long attemptedCents = details.has("attempted_cents") ? details.get("attempted_cents").getAsLong() : 0; //$NON-NLS-1$ //$NON-NLS-2$
                        String window = details.has("window") ? details.get("window").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$
                        String retryAtLocal = details.has("retry_at_local") ? details.get("retry_at_local").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$
                        long retryAfterSeconds = details.has("retry_after_seconds") ? details.get("retry_after_seconds").getAsLong() : 0; //$NON-NLS-1$ //$NON-NLS-2$
                        ex.setRateLimitDetails(new LlmProviderException.RateLimitDetails(
                                limitCents, usedCents, attemptedCents, window, retryAtLocal, retryAfterSeconds));
                    } catch (Exception ignored) {
                        // details parsing is best-effort
                    }
                }
                return ex;
            }
        } catch (Exception ignored) {
            // Fall through to raw error
        }
        return new LlmProviderException(
                "API error: " + statusCode + " - " + errorBody, null, statusCode, null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Resets the cancelled state.
     */
    protected void resetCancelled() {
        cancelled.set(false);
    }

    /**
     * Checks if the operation has been cancelled.
     *
     * @return true if cancelled
     */
    protected boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void dispose() {
        cancel();
        // HTTP client is managed by HttpClientFactory, no cleanup needed here
    }
}
