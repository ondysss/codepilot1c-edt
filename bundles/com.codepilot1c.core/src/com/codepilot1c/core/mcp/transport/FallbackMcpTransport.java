/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.model.McpMessage;

/**
 * Transport with one-time fallback from primary to secondary implementation.
 */
public class FallbackMcpTransport implements IMcpTransport {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(FallbackMcpTransport.class);

    private final IMcpTransport primary;
    private final IMcpTransport fallback;
    private volatile IMcpTransport active;

    public FallbackMcpTransport(IMcpTransport primary, IMcpTransport fallback) {
        this.primary = primary;
        this.fallback = fallback;
        this.active = primary;
    }

    @Override
    public void connect() throws IOException {
        active.connect();
    }

    @Override
    public void disconnect() {
        primary.disconnect();
        fallback.disconnect();
    }

    @Override
    public boolean isConnected() {
        return active.isConnected();
    }

    @Override
    public CompletableFuture<McpMessage> send(McpMessage message) {
        return active.send(message).handle((result, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(result);
            }
            if (!shouldFallback(error) || active == fallback) {
                return CompletableFuture.<McpMessage>failedFuture(unwrap(error));
            }
            LOG.warn("Switching MCP transport to legacy fallback mode"); //$NON-NLS-1$
            try {
                fallback.connect();
                active = fallback;
                return fallback.send(message);
            } catch (Exception e) {
                return CompletableFuture.<McpMessage>failedFuture(e);
            }
        }).thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<Void> sendNotification(McpMessage message) {
        return active.sendNotification(message);
    }

    @Override
    public void setNotificationHandler(Consumer<McpMessage> handler) {
        primary.setNotificationHandler(handler);
        fallback.setNotificationHandler(handler);
    }

    @Override
    public void setRequestHandler(Function<McpMessage, CompletableFuture<McpMessage>> handler) {
        primary.setRequestHandler(handler);
        fallback.setRequestHandler(handler);
    }

    @Override
    public CompletableFuture<Void> initializeSession() {
        return active.initializeSession();
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return active.shutdown();
    }

    @Override
    public void close() {
        try {
            primary.close();
        } catch (Exception e) {
            LOG.warn("Error closing primary MCP transport", e); //$NON-NLS-1$
        }
        try {
            fallback.close();
        } catch (Exception e) {
            LOG.warn("Error closing fallback MCP transport", e); //$NON-NLS-1$
        }
    }

    private boolean shouldFallback(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof McpHttpException httpError) {
            int code = httpError.getStatusCode();
            return code == 404 || code == 406 || code == 415 || code == 426;
        }
        String msg = cause.getMessage() != null ? cause.getMessage().toLowerCase() : ""; //$NON-NLS-1$
        return msg.contains("event-stream") || msg.contains("sse"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }
}
