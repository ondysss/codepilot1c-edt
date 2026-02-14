/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

import java.io.File;

import com.codepilot1c.core.mcp.config.McpServerConfig;

/**
 * Factory for creating MCP transport instances from server config.
 */
public class McpTransportFactory {

    /**
     * Creates transport for provided MCP server configuration.
     *
     * @param config server configuration
     * @return transport implementation
     */
    public IMcpTransport create(McpServerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null"); //$NON-NLS-1$
        }

        McpServerConfig.TransportType transportType = config.getTransportType();
        if (transportType == null) {
            transportType = McpServerConfig.TransportType.STDIO;
        }

        if (transportType == McpServerConfig.TransportType.STDIO) {
            return new McpStdioTransport(
                config.getCommand(),
                config.getArgs(),
                config.getEnv(),
                config.getWorkingDirectory() != null ? new File(config.getWorkingDirectory()) : null,
                config.getRequestTimeoutMs()
            );
        }

        throw new UnsupportedOperationException(
            "MCP remote transport is not available in this build: " + transportType.name()); //$NON-NLS-1$
    }
}
