/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.EnumSet;
import java.util.Set;

/**
 * Defines the core vs discoverable tool split for deferred tool loading.
 *
 * <p>Core tools are always included in the request tool surface.
 * Discoverable tools are only included after the LLM calls {@code discover_tools}
 * for the relevant category.</p>
 *
 * <p>This split is only active for providers that do NOT support
 * native deferred loading (i.e., OpenAI-compatible APIs like Qwen, Ollama).</p>
 */
public final class DeferredToolSet {

    /**
     * Categories that are always included (core tools).
     * These map to: read_file, list_files, edit_file, write_file, grep, glob,
     * git_inspect, git_mutate, task, skill, discover_tools.
     */
    public static final Set<ToolCategory> CORE_CATEGORIES = Set.of(
            ToolCategory.FILES_READ_SEARCH,
            ToolCategory.FILES_WRITE_EDIT);

    /**
     * Categories that are loaded on-demand via discover_tools.
     */
    public static final Set<ToolCategory> DISCOVERABLE_CATEGORIES = Set.copyOf(
            EnumSet.of(
                    ToolCategory.WORKSPACE_GIT_IMPORT,
                    ToolCategory.EDT_SEMANTIC_READ,
                    ToolCategory.METADATA_MUTATION,
                    ToolCategory.FORMS,
                    ToolCategory.EXTENSIONS_EXTERNALS,
                    ToolCategory.DCS,
                    ToolCategory.QA,
                    ToolCategory.SMOKE_RUNTIME_RECOVERY));

    /**
     * Tool names that are always core regardless of category.
     * Includes meta tools (task, skill, discover_tools) and
     * workspace/git tools that are fundamental.
     */
    public static final Set<String> ALWAYS_CORE_TOOLS = Set.of(
            "task", //$NON-NLS-1$
            "skill", //$NON-NLS-1$
            "discover_tools", //$NON-NLS-1$
            "delegate_to_agent", //$NON-NLS-1$
            "git_inspect", //$NON-NLS-1$
            "git_mutate"); //$NON-NLS-1$

    /**
     * User-facing category names for discover_tools command parameter.
     */
    public static final Set<String> CATEGORY_NAMES = Set.of(
            "bsl", "metadata", "forms", "extensions", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "dcs", "qa", "diagnostics", "workspace"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private DeferredToolSet() {
    }

    /**
     * Returns whether a tool should be included in the core surface.
     *
     * @param toolName the tool name
     * @param category the tool's resolved category
     * @return {@code true} if the tool is always core
     */
    public static boolean isCoreToolByName(String toolName, ToolCategory category) {
        if (ALWAYS_CORE_TOOLS.contains(toolName)) {
            return true;
        }
        return CORE_CATEGORIES.contains(category);
    }

    /**
     * Maps a user-facing category name to the internal {@link ToolCategory}.
     *
     * @param categoryName the name from discover_tools command
     * @return the matching category, or {@code null} if unknown
     */
    public static ToolCategory resolveCategory(String categoryName) {
        if (categoryName == null) {
            return null;
        }
        return switch (categoryName.toLowerCase(java.util.Locale.ROOT)) {
            case "bsl" -> ToolCategory.EDT_SEMANTIC_READ; //$NON-NLS-1$
            case "metadata" -> ToolCategory.METADATA_MUTATION; //$NON-NLS-1$
            case "forms" -> ToolCategory.FORMS; //$NON-NLS-1$
            case "extensions" -> ToolCategory.EXTENSIONS_EXTERNALS; //$NON-NLS-1$
            case "dcs" -> ToolCategory.DCS; //$NON-NLS-1$
            case "qa" -> ToolCategory.QA; //$NON-NLS-1$
            case "diagnostics" -> ToolCategory.SMOKE_RUNTIME_RECOVERY; //$NON-NLS-1$
            case "workspace" -> ToolCategory.WORKSPACE_GIT_IMPORT; //$NON-NLS-1$
            default -> null;
        };
    }
}
