/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.memory;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.memory.MemoryCategory;
import com.codepilot1c.core.memory.MemoryEntry;
import com.codepilot1c.core.memory.MemoryService;
import com.codepilot1c.core.memory.MemoryVisibility;
import com.codepilot1c.core.memory.RetentionPolicy;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Built-in tool for explicit fact storage into long-term memory.
 *
 * <p>The agent calls this tool when the user says "remember", "запомни",
 * or states an architecture decision, bug, or project-specific pattern.
 * Facts are saved immediately without waiting for session end.</p>
 *
 * <p>Flat parameter schema for provider-neutral tool-call compatibility.</p>
 */
@ToolMeta(
    name = "remember_fact",
    category = "memory",
    mutating = true,
    tags = {"memory", "workspace"}
)
public class RememberFactTool extends AbstractTool {

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "content": {
                        "type": "string",
                        "description": "Fact text to remember, one sentence, under 120 chars"
                    },
                    "category": {
                        "type": "string",
                        "enum": ["FACT", "ARCHITECTURE", "DECISION", "PATTERN", "BUG"],
                        "description": "Fact category (default: FACT)"
                    },
                    "domain": {
                        "type": "string",
                        "description": "Module or subsystem area (optional)"
                    }
                },
                "required": ["content"],
                "additionalProperties": false
            }
            """;

    @Override
    public String getDescription() {
        return "Save a project fact to long-term memory. Use when user says 'remember'/'запомни' or states architecture decisions, bugs, patterns."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return doExecute(params, ToolExecutionContext.unscoped());
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(
            ToolParameters params, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            String content;
            try {
                content = params.requireString("content"); //$NON-NLS-1$
            } catch (RuntimeException e) {
                return failure("INVALID_ARGUMENTS", e.getMessage()); //$NON-NLS-1$
            }
            if (content.isBlank()) {
                return failure("EMPTY_CONTENT", "content must not be empty"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            boolean truncated = false;
            if (content.length() > 500) {
                content = content.substring(0, 500);
                truncated = true;
            }

            String categoryStr = params.optString("category", "FACT"); //$NON-NLS-1$ //$NON-NLS-2$
            MemoryCategory category = parseCategory(categoryStr);

            String domain = params.optString("domain", null); //$NON-NLS-1$

            // Build the memory entry key from domain or category
            String key = domain != null && !domain.isBlank()
                    ? domain
                    : "Project Facts"; //$NON-NLS-1$

            MemoryEntry entry = MemoryEntry.builder(key, content)
                    .category(category)
                    .visibility(MemoryVisibility.MACHINE)
                    .retention(RetentionPolicy.DEFAULT_FACT_TTL)
                    .build();

            // Resolve project path from tool context
            String projectPath = resolveProjectPath(context);
            if (projectPath == null) {
                return failure("NO_ACTIVE_PROJECT", "No active project found"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            MemoryService.remember(projectPath, entry);
            log.info("RememberFactTool: saved fact [" + category + "] " + content); //$NON-NLS-1$ //$NON-NLS-2$

            JsonObject result = new JsonObject();
            result.addProperty("status", "saved"); //$NON-NLS-1$ //$NON-NLS-2$
            result.addProperty("content", content); //$NON-NLS-1$
            result.addProperty("category", category.name()); //$NON-NLS-1$
            result.addProperty("key", key); //$NON-NLS-1$
            result.addProperty("project_path", projectPath); //$NON-NLS-1$
            result.addProperty("truncated", truncated); //$NON-NLS-1$
            if (domain != null && !domain.isBlank()) {
                result.addProperty("domain", domain); //$NON-NLS-1$
            }
            return ToolResult.success(result.toString(), result);
        });
    }

    private static MemoryCategory parseCategory(String str) {
        if (str == null || str.isBlank()) {
            return MemoryCategory.FACT;
        }
        try {
            return MemoryCategory.valueOf(str.toUpperCase().strip());
        } catch (IllegalArgumentException e) {
            return MemoryCategory.FACT;
        }
    }

    /**
     * Resolves the active project path from the workspace.
     * Falls back to first open project if no specific context is available.
     */
    private String resolveProjectPath(ToolExecutionContext context) {
        return ActiveProjectSupport.resolveProjectPath(context);
    }

    private static ToolResult failure(String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("code", code); //$NON-NLS-1$
        result.addProperty("message", message != null ? message : ""); //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.failure(result.toString());
    }
}
