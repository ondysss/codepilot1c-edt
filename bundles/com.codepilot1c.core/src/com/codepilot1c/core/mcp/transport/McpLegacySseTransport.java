/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

import java.util.Map;

import com.codepilot1c.core.mcp.auth.McpAuthHttpInterceptor;

/**
 * Legacy MCP HTTP+SSE transport mode.
 */
public class McpLegacySseTransport extends McpStreamableHttpTransport {

    public McpLegacySseTransport(String endpointUrl,
                                 Map<String, String> staticHeaders,
                                 McpAuthHttpInterceptor authInterceptor,
                                 int requestTimeoutMs,
                                 boolean allowInsecureHttp) {
        super(endpointUrl, staticHeaders, authInterceptor, requestTimeoutMs, true, allowInsecureHttp);
    }
}
