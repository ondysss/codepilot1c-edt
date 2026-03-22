/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.EnumMap;
import java.util.Map;

/**
 * Adds backend-specific execution discipline for built-in tools.
 */
public final class QwenToolSurfaceContributor implements ToolSurfaceContributor {

    private static final Map<ToolCategory, String> CATEGORY_GUIDANCE = createGuidance();

    @Override
    public boolean supports(ToolSurfaceContext context) {
        return context != null
                && context.isBuiltIn()
                && context.isBackendSelectedInUi()
                && context.getCategory() != ToolCategory.DYNAMIC;
    }

    @Override
    public void contribute(ToolSurfaceContext context, com.codepilot1c.core.model.ToolDefinition.Builder builder) {
        String template = CATEGORY_GUIDANCE.get(context.getCategory());
        if (template == null || builder.getDescription() == null || builder.getDescription().contains(template)) {
            return;
        }
        builder.description(builder.getDescription() + "\n\n" + template); //$NON-NLS-1$
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private static Map<ToolCategory, String> createGuidance() {
        Map<ToolCategory, String> guidance = new EnumMap<>(ToolCategory.class);
        guidance.put(ToolCategory.FILES_READ_SEARCH,
                "Qwen routing: prefer read/search before mutation, keep paths workspace-relative, and switch to EDT semantic tools for platform/model questions."); //$NON-NLS-1$
        guidance.put(ToolCategory.FILES_WRITE_EDIT,
                "Qwen routing: read before edit, patch the smallest necessary region, and do not mutate EDT metadata files directly when a semantic tool exists."); //$NON-NLS-1$
        guidance.put(ToolCategory.WORKSPACE_GIT_IMPORT,
                "Qwen routing: inspect first, prefer read-only Git/workspace discovery before mutating repositories or imports, and report concrete state transitions."); //$NON-NLS-1$
        guidance.put(ToolCategory.EDT_SEMANTIC_READ,
                "Qwen routing: prefer semantic EDT answers over raw grep when the question is about symbols, metadata, types, or platform behavior."); //$NON-NLS-1$
        guidance.put(ToolCategory.METADATA_MUTATION,
                "Qwen routing: enforce edt_validate_request -> validation_token -> mutation -> diagnostics. Do not skip validation or diagnose success without re-running diagnostics."); //$NON-NLS-1$
        guidance.put(ToolCategory.FORMS,
                "Qwen routing: inspect or validate before mutating forms, preserve aliases accepted by the tool, and follow form mutation with diagnostics when applicable."); //$NON-NLS-1$
        guidance.put(ToolCategory.EXTENSIONS_EXTERNALS,
                "Qwen routing: list or inspect project/object state before create/adopt/update operations, and keep project scope explicit in arguments."); //$NON-NLS-1$
        guidance.put(ToolCategory.DCS,
                "Qwen routing: summarize/list before DCS mutation, keep node identities stable, and re-check resulting diagnostics after structural edits."); //$NON-NLS-1$
        guidance.put(ToolCategory.QA,
                "Qwen routing: follow the QA pipeline in order, treat generated context as ephemeral, and use steps search only as fallback support for scenario authoring."); //$NON-NLS-1$
        guidance.put(ToolCategory.SMOKE_RUNTIME_RECOVERY,
                "Qwen routing: use smoke and recovery tools only for verification or repair flows, and report the exact runtime symptom being checked or recovered."); //$NON-NLS-1$
        return guidance;
    }
}
