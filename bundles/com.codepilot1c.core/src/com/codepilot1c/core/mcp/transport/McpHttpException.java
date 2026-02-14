/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

import com.codepilot1c.core.mcp.model.McpException;

/**
 * HTTP-level MCP error.
 */
public class McpHttpException extends McpException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public McpHttpException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public McpHttpException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
