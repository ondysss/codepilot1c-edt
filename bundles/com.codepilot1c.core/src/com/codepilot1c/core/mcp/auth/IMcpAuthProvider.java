/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.auth;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Provides auth headers for MCP remote transports.
 */
public interface IMcpAuthProvider {

    /**
     * Resolves authorization headers to inject into outgoing requests.
     *
     * @return auth headers
     */
    CompletableFuture<Map<String, String>> getAuthHeaders();

    /**
     * Invalidates current credentials (e.g., after invalid_grant).
     */
    default void invalidate() {
        // Optional.
    }
}
