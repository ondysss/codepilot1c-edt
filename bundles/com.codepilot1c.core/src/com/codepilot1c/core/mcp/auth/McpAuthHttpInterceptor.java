/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.auth;

import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Applies auth headers to HTTP requests.
 */
public class McpAuthHttpInterceptor {

    private final IMcpAuthProvider authProvider;

    public McpAuthHttpInterceptor(IMcpAuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    public CompletableFuture<Void> apply(HttpRequest.Builder builder) {
        if (authProvider == null) {
            return CompletableFuture.completedFuture(null);
        }
        return authProvider.getAuthHeaders().thenAccept(headers -> {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        });
    }
}
