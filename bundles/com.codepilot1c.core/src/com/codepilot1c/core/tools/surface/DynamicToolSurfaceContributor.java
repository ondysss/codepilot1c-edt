/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

/**
 * Minimal backend-aware augmentation for dynamic/MCP-contributed tools.
 */
public final class DynamicToolSurfaceContributor implements ToolSurfaceContributor {

    private static final String MCP_GUIDANCE =
            "Backend note: this tool is provided by an MCP/dynamic source. Follow its schema exactly, " //$NON-NLS-1$
            + "do not assume EDT/file semantics, and rely on returned machine-readable errors."; //$NON-NLS-1$

    @Override
    public boolean supports(ToolSurfaceContext context) {
        return context != null && context.isBackendSelectedInUi() && !context.isBuiltIn();
    }

    @Override
    public void contribute(ToolSurfaceContext context, com.codepilot1c.core.model.ToolDefinition.Builder builder) {
        if (builder.getDescription() == null || builder.getDescription().contains(MCP_GUIDANCE)) {
            builder.parametersSchema(ToolSurfaceSchemaNormalizer.normalizeDynamic(builder.getParametersSchema()));
            return;
        }
        builder.description(builder.getDescription() + "\n\n" + MCP_GUIDANCE); //$NON-NLS-1$
        builder.parametersSchema(ToolSurfaceSchemaNormalizer.normalizeDynamic(builder.getParametersSchema()));
    }
}
