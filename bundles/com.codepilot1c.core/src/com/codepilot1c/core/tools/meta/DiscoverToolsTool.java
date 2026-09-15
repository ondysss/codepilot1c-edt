/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.mcp.host.McpToolVisibility;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.BuiltinToolTaxonomy;
import com.codepilot1c.core.tools.surface.DeferredToolSession;
import com.codepilot1c.core.tools.surface.DeferredToolSet;
import com.codepilot1c.core.tools.surface.ToolCategory;
import com.codepilot1c.core.tools.surface.ToolSurfaceContext;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Meta-tool for discovering domain-specific tools by category.
 *
 * <p>When deferred loading is active, the LLM sees only core tools
 * (~11) plus this tool. To access domain-specific tools (BSL, metadata,
 * forms, DCS, QA, etc.), the LLM calls this tool with the desired
 * category. The tools are then injected into subsequent requests.</p>
 *
 * <p>Categories: bsl, metadata, forms, extensions, dcs, qa,
 * diagnostics, workspace.</p>
 */
@ToolMeta(name = "discover_tools", category = "general",
        tags = {"meta"})
public class DiscoverToolsTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "category": {
                  "type": "string",
                  "description": "Tool category to discover: bsl|metadata|forms|extensions|dcs|qa|diagnostics|workspace",
                  "enum": ["bsl", "metadata", "forms", "extensions", "dcs", "qa", "diagnostics", "workspace"]
                }
              },
              "required": ["category"]
            }
            """; //$NON-NLS-1$

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ToolRegistry toolRegistry;
    private volatile DeferredToolSession session;

    public DiscoverToolsTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Sets the deferred tool session for tracking discovered categories.
     *
     * @param session the session (must not be null)
     */
    public void setSession(DeferredToolSession session) {
        this.session = session;
    }

    /**
     * Returns the current session, or null if not set.
     */
    public DeferredToolSession getSession() {
        return session;
    }

    @Override
    public String getDescription() {
        return "Показывает скрытые domain tools по категории, когда текущей поверхности недостаточно. Сам работу не выполняет, только раскрывает инструменты."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return doExecute(params, ToolExecutionContext.unscoped());
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(
            ToolParameters params, ToolExecutionContext context) {
        Map<String, Object> p = params.getRaw();
        String categoryName = p.get("category") != null //$NON-NLS-1$
                ? String.valueOf(p.get("category")) : null; //$NON-NLS-1$

        if (categoryName == null || !DeferredToolSet.CATEGORY_NAMES.contains(
                categoryName.toLowerCase(java.util.Locale.ROOT))) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Unknown category: " + categoryName + //$NON-NLS-1$
                            ". Available: " + String.join(", ", DeferredToolSet.CATEGORY_NAMES))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        ToolCategory category = DeferredToolSet.resolveCategory(categoryName);
        if (category == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("Could not resolve category: " + categoryName)); //$NON-NLS-1$
        }

        // Mark category as discovered in the session
        DeferredToolSession currentSession = this.session;
        if (currentSession != null) {
            currentSession.markDiscovered(category);
        }

        // Collect tools for this category
        Discovered discovered = collect(category, visibilityFor(context));
        List<ToolSummary> toolSummaries = discovered.tools();

        if (toolSummaries.isEmpty()) {
            // "Нет в этой рабочей области" и "снято настройкой" - разные вещи, и первое
            // сбивает с толку: инструменты есть, просто их не отдают.
            String message = discovered.hiddenByPolicy() > 0
                    ? "No tools available for category " + categoryName + ": all " //$NON-NLS-1$ //$NON-NLS-2$
                            + discovered.hiddenByPolicy()
                            + " are hidden by the MCP host policy (exposedTools or session profile)." //$NON-NLS-1$
                    : "No tools found for category " + categoryName //$NON-NLS-1$
                            + ": this category may not be available in the current workspace."; //$NON-NLS-1$
            return CompletableFuture.completedFuture(ToolResult.success(message));
        }

        // Build response
        JsonObject result = new JsonObject();
        result.addProperty("category", categoryName); //$NON-NLS-1$
        result.addProperty("tools_count", toolSummaries.size()); //$NON-NLS-1$
        result.addProperty("status", "discovered"); //$NON-NLS-1$ //$NON-NLS-2$
        if (discovered.hiddenByPolicy() > 0) {
            result.addProperty("hidden_by_policy", discovered.hiddenByPolicy()); //$NON-NLS-1$
        }

        JsonArray toolsArray = new JsonArray();
        for (ToolSummary summary : toolSummaries) {
            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("name", summary.name); //$NON-NLS-1$
            toolObj.addProperty("description", summary.description); //$NON-NLS-1$
            toolObj.addProperty("has_parameters", summary.hasParameters); //$NON-NLS-1$
            toolsArray.add(toolObj);
        }
        result.add("tools", toolsArray); //$NON-NLS-1$
        result.addProperty("note", //$NON-NLS-1$
                "These tools are now available. You can call them directly in subsequent messages."); //$NON-NLS-1$

        return CompletableFuture.completedFuture(
                ToolResult.success(GSON.toJson(result)));
    }

    /**
     * Инструменты категории, видимые вызывающему.
     *
     * <p>Список должен совпадать с тем, что отдаёт tools/list: снятое политикой
     * exposedTools позвать нельзя, а ответ обещал обратное - "You can call them directly".
     */
    Discovered collect(ToolCategory category, McpToolVisibility visibility) {
        List<ToolSummary> summaries = new ArrayList<>();
        int hidden = 0;
        ToolSurfaceContext surfaceContext = toolRegistry.createRuntimeSurfaceContext(
                ToolSurfaceContext.defaultProfile());

        for (ToolRegistry.ToolResolution resolution
                : toolRegistry.getModelFacingToolResolutions()) {
            ITool tool = resolution.tool();
            if (BuiltinToolTaxonomy.categoryOf(tool) != category) {
                continue;
            }
            if (visibility != null && !visibility.isVisible(resolution)) {
                hidden++;
                continue;
            }
            ToolDefinition def = toolRegistry.getToolDefinition(tool, surfaceContext);
            summaries.add(new ToolSummary(
                    def.getName(),
                    def.getDescription(),
                    def.getParametersSchema() != null));
        }
        return new Discovered(summaries, hidden);
    }

    /** Найденные инструменты и сколько их скрыла политика. */
    record Discovered(List<ToolSummary> tools, int hiddenByPolicy) { }

    // Настройки хоста касаются только вызовов снаружи, а признак "снаружи" даёт контекст:
    // чат EDT и его подагенты приходят с путём проекта и идентификатором сессии, клиент MCP -
    // без них. По полю session это определять нельзя: DiscoverToolsTool живёт в общем реестре
    // одним экземпляром, и сессию, выставленную чатом однажды, увидели бы и вызовы снаружи.
    private McpToolVisibility visibilityFor(ToolExecutionContext context) {
        if (context != null && context.hasProjectIdentity()) {
            return null;
        }
        return McpToolVisibility.fromHostConfig();
    }

    record ToolSummary(String name, String description, boolean hasParameters) {
    }
}
