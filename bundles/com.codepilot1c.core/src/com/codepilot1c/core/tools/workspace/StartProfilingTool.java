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
import com.codepilot1c.core.edt.ast.StartProfilingRequest;
import com.codepilot1c.core.edt.ast.StartProfilingResult;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Toggle performance measurement (замер производительности) on the active debug target.
 */
@ToolMeta(
        name = "start_profiling",
        category = "workspace",
        surfaceCategory = "edt_semantic_write",
        mutating = true,
        tags = {"workspace", "debug", "profiling"})
public class StartProfilingTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(StartProfilingTool.class);
    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "enabled": {"type": "boolean", "description": "Enable or disable profiling (true=enable, false=disable, null=toggle)"}
              },
              "required": ["projectName"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Запустить или остановить профилировщик производительности на активной debug target."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("start-profiling"); //$NON-NLS-1$
            Map<String, Object> parameters = params.getRaw();
            try {
                StartProfilingRequest request = StartProfilingRequest.fromParameters(parameters);
                LOG.info("[%s] START start_profiling project=%s enabled=%s", //$NON-NLS-1$
                        opId, request.getProjectName(), request.getEnabled());
                StartProfilingResult result = EdtAstServices.getInstance().startProfiling(request);
                JsonObject structured = GSON.toJsonTree(result).getAsJsonObject();
                LOG.info("[%s] DONE start_profiling profilingEnabled=%d status=%s", //$NON-NLS-1$
                        opId, result.isProfilingEnabled(), result.getStatus());
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION, structured);
            } catch (EdtAstException e) {
                LOG.warn("[%s] start_profiling failed: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                LOG.error("[" + opId + "] start_profiling failed", e); //$NON-NLS-1$ //$NON-NLS-2$
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
