/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.auth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Auth provider that returns static configured headers.
 */
public class StaticHeadersAuthProvider implements IMcpAuthProvider {

    private final Map<String, String> headers;

    public StaticHeadersAuthProvider(Map<String, String> headers) {
        this.headers = headers != null ? new HashMap<>(headers) : Collections.emptyMap();
    }

    @Override
    public CompletableFuture<Map<String, String>> getAuthHeaders() {
        return CompletableFuture.completedFuture(Collections.unmodifiableMap(headers));
    }
}
