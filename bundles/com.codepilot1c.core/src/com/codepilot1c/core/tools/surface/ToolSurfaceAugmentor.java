/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ITool;

/**
 * Central assembly point for model-facing tool definitions.
 */
public class ToolSurfaceAugmentor {

    private final List<ToolSurfaceContributor> contributors;

    public ToolSurfaceAugmentor(List<ToolSurfaceContributor> contributors) {
        this.contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors")).stream() //$NON-NLS-1$
                .sorted(Comparator.comparingInt(ToolSurfaceContributor::getOrder))
                .toList();
    }

    public static ToolSurfaceAugmentor passthrough() {
        return new ToolSurfaceAugmentor(List.of());
    }

    public static ToolSurfaceAugmentor defaultAugmentor() {
        return new ToolSurfaceAugmentor(List.of(
                new QwenToolSurfaceRewriteContributor(),
                new QwenToolSurfaceContributor(),
                new DynamicToolSurfaceContributor()));
    }

    public ToolDefinition augment(ITool tool, ToolSurfaceContext context) {
        Objects.requireNonNull(tool, "tool"); //$NON-NLS-1$
        Objects.requireNonNull(context, "context"); //$NON-NLS-1$

        ToolDefinition.Builder builder = ToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .parametersSchema(tool.getParameterSchema());

        for (ToolSurfaceContributor contributor : contributors) {
            if (contributor.supports(context)) {
                contributor.contribute(context, builder);
            }
        }

        builder.name(tool.getName());
        return builder.build();
    }
}
