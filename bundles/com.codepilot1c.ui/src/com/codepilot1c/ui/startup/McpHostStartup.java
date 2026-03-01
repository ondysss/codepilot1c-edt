/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.startup;

import java.util.concurrent.CompletableFuture;

import org.eclipse.ui.IStartup;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.McpHostManager;

public class McpHostStartup implements IStartup {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostStartup.class);

    @Override
    public void earlyStartup() {
        CompletableFuture.runAsync(() -> {
            try {
                McpHostManager.getInstance().startIfEnabled();
            } catch (Exception e) {
                LOG.error("Failed to auto-start MCP host", e); //$NON-NLS-1$
            }
        });
    }
}
