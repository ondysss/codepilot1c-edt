/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.auth;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * No-op auth provider.
 */
public class NoAuthProvider implements IMcpAuthProvider {

    @Override
    public CompletableFuture<Map<String, String>> getAuthHeaders() {
        return CompletableFuture.completedFuture(Collections.emptyMap());
    }
}
