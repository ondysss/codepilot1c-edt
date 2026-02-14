/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

/**
 * Stores MCP remote session id.
 */
public class McpSessionManager {

    private volatile String sessionId;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void clear() {
        this.sessionId = null;
    }
}
