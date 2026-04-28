/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.codepilot1c.core.edt.ast.GetBookmarksRequest;
import com.codepilot1c.core.edt.ast.GetBookmarksResult;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Returns Eclipse bookmark markers for an EDT project.
 */
@ToolMeta(
        name = "get_bookmarks",
        category = "workspace",
        surfaceCategory = "edt_semantic_read",
        mutating = false,
        tags = {"read-only", "workspace", "edt"})
public class GetBookmarksTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetBookmarksTool.class);
    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "limit": {"type": "integer", "description": "Maximum bookmarks to return (1..1000, default 100)"}
              },
              "required": ["projectName"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Возвращает закладки Eclipse/EDT проекта: файл, строка, сообщение, тип и приоритет."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("get-bookmarks"); //$NON-NLS-1$
            Map<String, Object> parameters = params.getRaw();
            try {
                GetBookmarksRequest request = GetBookmarksRequest.fromParameters(parameters);
                LOG.info("[%s] START get_bookmarks project=%s limit=%d", //$NON-NLS-1$
                        opId, request.getProjectName(), request.getLimit());
                GetBookmarksResult result = EdtAstServices.getInstance().getBookmarks(request);
                JsonObject structured = GSON.toJsonTree(result).getAsJsonObject();
                LOG.info("[%s] DONE get_bookmarks total=%d returned=%d", //$NON-NLS-1$
                        opId, result.getTotal(), result.getBookmarks().size());
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS, structured);
            } catch (EdtAstException e) {
                LOG.warn("[%s] get_bookmarks failed: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                LOG.error("[" + opId + "] get_bookmarks failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure(errorJson("INTERNAL_ERROR", e.getMessage(), false)); //$NON-NLS-1$
            }
        });
    }

    private static String toErrorJson(EdtAstException e) {
        return errorJson(e.getCode().name(), e.getMessage(), e.isRecoverable());
    }

    private static String errorJson(String code, String message, boolean recoverable) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", code == null ? "" : code); //$NON-NLS-1$ //$NON-NLS-2$
        obj.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        obj.addProperty("recoverable", recoverable); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
