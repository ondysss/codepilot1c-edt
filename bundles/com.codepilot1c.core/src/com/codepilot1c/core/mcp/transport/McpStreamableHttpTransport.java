/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.auth.McpAuthHttpInterceptor;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.google.gson.Gson;

/**
 * Remote MCP transport via Streamable HTTP.
 */
public class McpStreamableHttpTransport implements IMcpTransport {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpStreamableHttpTransport.class);
    private static final String HEADER_SESSION_ID = "Mcp-Session-Id"; //$NON-NLS-1$

    private final URI endpointUri;
    private final Map<String, String> staticHeaders;
    private final int requestTimeoutMs;
    private final HttpClient httpClient;
    private final McpAuthHttpInterceptor authInterceptor;
    private final McpSessionManager sessionManager = new McpSessionManager();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final Gson gson = new Gson();
    private final boolean legacySseMode;
    private final boolean allowInsecureHttp;

    private volatile boolean connected = false;
    private Consumer<McpMessage> notificationHandler;
    private Function<McpMessage, CompletableFuture<McpMessage>> requestHandler;

    public McpStreamableHttpTransport(String endpointUrl,
                                      Map<String, String> staticHeaders,
                                      McpAuthHttpInterceptor authInterceptor,
                                      int requestTimeoutMs) {
        this(endpointUrl, staticHeaders, authInterceptor, requestTimeoutMs, false, false);
    }

    public McpStreamableHttpTransport(String endpointUrl,
                                      Map<String, String> staticHeaders,
                                      McpAuthHttpInterceptor authInterceptor,
                                      int requestTimeoutMs,
                                      boolean legacySseMode,
                                      boolean allowInsecureHttp) {
        this.endpointUri = URI.create(endpointUrl);
        this.staticHeaders = staticHeaders != null ? new HashMap<>(staticHeaders) : Map.of();
        this.authInterceptor = authInterceptor;
        this.requestTimeoutMs = requestTimeoutMs > 0 ? requestTimeoutMs : 60000;
        this.legacySseMode = legacySseMode;
        this.allowInsecureHttp = allowInsecureHttp;
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        this.httpClient = plugin != null && plugin.getHttpClientFactory() != null
            ? plugin.getHttpClientFactory().getSharedClient()
            : HttpClient.newHttpClient();
    }

    @Override
    public void connect() throws IOException {
        validateEndpoint();
        connected = true;
        LOG.info("MCP HTTP transport connected: %s", endpointUri); //$NON-NLS-1$
    }

    private void validateEndpoint() throws IOException {
        String scheme = endpointUri.getScheme();
        if (scheme == null) {
            throw new IOException("MCP endpoint URL must include scheme"); //$NON-NLS-1$
        }
        if ("https".equalsIgnoreCase(scheme)) { //$NON-NLS-1$
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && allowInsecureHttp) { //$NON-NLS-1$
            return;
        }
        throw new IOException("Only https MCP endpoints are allowed by default: " + endpointUri); //$NON-NLS-1$
    }

    @Override
    public void disconnect() {
        connected = false;
        sessionManager.clear();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public CompletableFuture<McpMessage> send(McpMessage message) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IOException("Transport not connected")); //$NON-NLS-1$
        }
        if (message.getRawId() == null) {
            message.setRawId(requestIdCounter.getAndIncrement());
        }
        String body = gson.toJson(message);
        return execute(body).thenApply(response -> {
            McpMessage parsed = parseResponse(response);
            if (parsed != null && parsed.isNotification() && notificationHandler != null) {
                notificationHandler.accept(parsed);
            }
            if (parsed != null && parsed.isRequest() && requestHandler != null) {
                requestHandler.apply(parsed);
            }
            return parsed;
        });
    }

    @Override
    public CompletableFuture<Void> sendNotification(McpMessage message) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IOException("Transport not connected")); //$NON-NLS-1$
        }
        String body = gson.toJson(message);
        return execute(body).thenApply(r -> null);
    }

    private CompletableFuture<String> execute(String requestBody) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpointUri)
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .header("Accept", legacySseMode ? "text/event-stream" : "application/json, text/event-stream") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        String sessionId = sessionManager.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            requestBuilder.header(HEADER_SESSION_ID, sessionId);
        }
        for (Map.Entry<String, String> e : staticHeaders.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                requestBuilder.header(e.getKey(), e.getValue());
            }
        }

        CompletableFuture<Void> authFuture = authInterceptor != null
            ? authInterceptor.apply(requestBuilder)
            : CompletableFuture.completedFuture(null);
        return authFuture.thenCompose(v -> httpClient.sendAsync(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString()
        )).thenApply(response -> {
            String newSessionId = response.headers().firstValue(HEADER_SESSION_ID).orElse(null);
            if (newSessionId != null && !newSessionId.isBlank()) {
                sessionManager.setSessionId(newSessionId);
            }
            int status = response.statusCode();
            if (status == 404) {
                sessionManager.clear();
                throw new CompletionException(new McpHttpException("MCP session expired (404)", status)); //$NON-NLS-1$
            }
            if (status / 100 != 2) {
                throw new CompletionException(new McpHttpException(
                    "MCP HTTP request failed: " + status + " " + response.body(), status)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return response.body();
        });
    }

    private McpMessage parseResponse(String body) {
        if (body == null || body.isBlank()) {
            return new McpMessage();
        }
        String trimmed = body.trim();
        String sseJson = extractFirstSseJson(trimmed);
        if (sseJson != null && !sseJson.isBlank()) {
            return gson.fromJson(sseJson, McpMessage.class);
        }
        return gson.fromJson(trimmed, McpMessage.class);
    }

    private String extractFirstSseJson(String ssePayload) {
        // Streamable HTTP responses may include SSE framing like:
        // id:1
        // event:message
        // data:{...json...}
        // We accept payloads where data: is not the first line.
        String[] lines = ssePayload.split("\\R"); //$NON-NLS-1$
        for (String line : lines) {
            if (line.startsWith("data:")) { //$NON-NLS-1$
                String json = line.substring(5).trim();
                if (!json.isEmpty()) {
                    return json;
                }
            }
        }
        return null;
    }

    @Override
    public void setNotificationHandler(Consumer<McpMessage> handler) {
        this.notificationHandler = handler;
    }

    @Override
    public void setRequestHandler(Function<McpMessage, CompletableFuture<McpMessage>> handler) {
        this.requestHandler = handler;
    }

    @Override
    public CompletableFuture<Void> initializeSession() {
        sessionManager.clear();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        sessionManager.clear();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        disconnect();
    }
}
