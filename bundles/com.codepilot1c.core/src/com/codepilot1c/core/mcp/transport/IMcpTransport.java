/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import com.codepilot1c.core.mcp.model.McpMessage;

/**
 * Transport interface for MCP communication.
 *
 * <p>Implementations handle the low-level communication with MCP servers,
 * such as STDIO or HTTP/SSE.</p>
 */
public interface IMcpTransport extends AutoCloseable {

    /**
     * Connects to the MCP server.
     *
     * @throws IOException if connection fails
     */
    void connect() throws IOException;

    /**
     * Disconnects from the MCP server.
     */
    void disconnect();

    /**
     * Returns whether the transport is connected.
     *
     * @return true if connected
     */
    boolean isConnected();

    /**
     * Sends a request message and returns a future for the response.
     *
     * <p>The transport assigns a unique ID to the message and waits for
     * the corresponding response from the server.</p>
     *
     * @param message the request message to send
     * @return a future containing the response
     */
    CompletableFuture<McpMessage> send(McpMessage message);

    /**
     * Sends a notification message (no response expected).
     *
     * <p>Notifications are fire-and-forget messages that don't receive responses.</p>
     *
     * @param message the notification message to send
     * @return a future that completes when the message is sent
     */
    CompletableFuture<Void> sendNotification(McpMessage message);

    /**
     * Sets the handler for server notifications.
     *
     * @param handler the notification handler
     */
    void setNotificationHandler(Consumer<McpMessage> handler);

    /**
     * Sets the handler for server requests (messages with method and id).
     *
     * @param handler request handler that returns JSON-RPC response message
     */
    default void setRequestHandler(Function<McpMessage, CompletableFuture<McpMessage>> handler) {
        // Optional for transports.
    }

    /**
     * Optional transport-specific session initialization hook.
     *
     * @return completed future when session initialization is done
     */
    default CompletableFuture<Void> initializeSession() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Optional transport-specific graceful shutdown hook.
     *
     * @return completed future when shutdown is done
     */
    default CompletableFuture<Void> shutdown() {
        return CompletableFuture.completedFuture(null);
    }
}
