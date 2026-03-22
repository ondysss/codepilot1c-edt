/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import com.codepilot1c.core.model.ToolDefinition;

/**
 * Contract for provider-aware augmentation of model-facing tool definitions.
 */
public interface ToolSurfaceContributor {

    boolean supports(ToolSurfaceContext context);

    void contribute(ToolSurfaceContext context, ToolDefinition.Builder builder);

    default int getOrder() {
        return 0;
    }
}
