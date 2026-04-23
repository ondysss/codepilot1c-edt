/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.Locale;
import java.util.Optional;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Public tool-surface taxonomy backed by runtime tool metadata.
 */
public final class BuiltinToolTaxonomy {

    private BuiltinToolTaxonomy() {
    }

    public static ToolCategory categoryOf(ITool tool) {
        if (tool == null) {
            return ToolCategory.DYNAMIC;
        }
        ToolCategory category = explicitSurfaceCategory(tool.getSurfaceCategory())
                .orElseGet(() -> inferredCategory(tool).orElse(null));
        if (category != null) {
            return category;
        }
        return ToolCategory.DYNAMIC;
    }

    public static ToolCategory categoryOf(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ToolCategory.DYNAMIC;
        }
        ITool tool = lookupTool(toolName);
        return tool != null ? categoryOf(tool) : ToolCategory.DYNAMIC;
    }

    public static Optional<ToolCategory> find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        ITool tool = lookupTool(toolName);
        return tool != null ? Optional.of(categoryOf(tool)) : Optional.empty();
    }

    public static boolean isKnownBuiltin(String toolName) {
        return lookupTool(toolName) != null;
    }

    private static Optional<ToolCategory> explicitSurfaceCategory(String rawSurfaceCategory) {
        if (rawSurfaceCategory == null || rawSurfaceCategory.isBlank()) {
            return Optional.empty();
        }
        return switch (rawSurfaceCategory.trim().toLowerCase(Locale.ROOT)) {
            case "files_read_search" -> Optional.of(ToolCategory.FILES_READ_SEARCH); //$NON-NLS-1$
            case "files_write_edit" -> Optional.of(ToolCategory.FILES_WRITE_EDIT); //$NON-NLS-1$
            case "workspace_git_import" -> Optional.of(ToolCategory.WORKSPACE_GIT_IMPORT); //$NON-NLS-1$
            case "edt_semantic_read" -> Optional.of(ToolCategory.EDT_SEMANTIC_READ); //$NON-NLS-1$
            case "metadata_mutation" -> Optional.of(ToolCategory.METADATA_MUTATION); //$NON-NLS-1$
            case "forms" -> Optional.of(ToolCategory.FORMS); //$NON-NLS-1$
            case "extensions_externals" -> Optional.of(ToolCategory.EXTENSIONS_EXTERNALS); //$NON-NLS-1$
            case "dcs" -> Optional.of(ToolCategory.DCS); //$NON-NLS-1$
            case "qa" -> Optional.of(ToolCategory.QA); //$NON-NLS-1$
            case "smoke_runtime_recovery" -> Optional.of(ToolCategory.SMOKE_RUNTIME_RECOVERY); //$NON-NLS-1$
            case "dynamic" -> Optional.of(ToolCategory.DYNAMIC); //$NON-NLS-1$
            default -> Optional.empty();
        };
    }

    private static Optional<ToolCategory> inferredCategory(ITool tool) {
        String rawCategory = tool.getCategory();
        if (rawCategory == null || rawCategory.isBlank()) {
            return Optional.empty();
        }
        return switch (rawCategory.trim().toLowerCase(Locale.ROOT)) {
            case "file", "files" -> Optional.of(
                    tool.isMutating() ? ToolCategory.FILES_WRITE_EDIT : ToolCategory.FILES_READ_SEARCH); //$NON-NLS-1$ //$NON-NLS-2$
            case "workspace", "git" -> Optional.of(ToolCategory.WORKSPACE_GIT_IMPORT); //$NON-NLS-1$ //$NON-NLS-2$
            case "bsl", "edt" -> Optional.of(ToolCategory.EDT_SEMANTIC_READ); //$NON-NLS-1$ //$NON-NLS-2$
            case "metadata" -> Optional.of(ToolCategory.METADATA_MUTATION); //$NON-NLS-1$
            case "forms", "form" -> Optional.of(ToolCategory.FORMS); //$NON-NLS-1$ //$NON-NLS-2$
            case "extension", "extensions", "external" -> Optional.of(ToolCategory.EXTENSIONS_EXTERNALS); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dcs" -> Optional.of(ToolCategory.DCS); //$NON-NLS-1$
            case "qa" -> Optional.of(ToolCategory.QA); //$NON-NLS-1$
            case "diagnostics", "diagnostic" -> Optional.of(ToolCategory.SMOKE_RUNTIME_RECOVERY); //$NON-NLS-1$ //$NON-NLS-2$
            case "general" -> Optional.empty(); //$NON-NLS-1$
            default -> Optional.empty();
        };
    }

    private static ITool lookupTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        try {
            return ToolRegistry.getInstance().getTool(toolName);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
