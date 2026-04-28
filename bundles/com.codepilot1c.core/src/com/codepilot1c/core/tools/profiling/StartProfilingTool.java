/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.profiling;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.profiling.EdtProfilingService;
import com.codepilot1c.core.edt.profiling.ProfilingException;
import com.codepilot1c.core.edt.profiling.StartProfilingRequest;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Toggles EDT 1C runtime profiling on an active debug target.
 */
@ToolMeta(
        name = "start_profiling",
        category = "debugging",
        surfaceCategory = "debugging",
        mutating = true,
        tags = {"workspace", "debug", "profiling"})
public class StartProfilingTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(StartProfilingTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "applicationId": {
                  "type": "string",
                  "description": "Optional EDT debug application id; omit when exactly one debug target is active"
                }
              },
              "required": []
            }
            """; //$NON-NLS-1$

    private final EdtProfilingService service;

    public StartProfilingTool() {
        this(new EdtProfilingService());
    }

    StartProfilingTool(EdtProfilingService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Toggles EDT 1C profiling on an active debug target; get results after running code."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("profiling-start"); //$NON-NLS-1$
            LOG.info("[%s] START start_profiling", opId); //$NON-NLS-1$
            try {
                JsonObject result = GSON.toJsonTree(service.startProfiling(
                        StartProfilingRequest.fromParameters(params.getRaw()), opId)).getAsJsonObject();
                LOG.info("[%s] DONE start_profiling", opId); //$NON-NLS-1$
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CODE, result);
            } catch (ProfilingException e) {
                LOG.warn("[%s] start_profiling failed: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(GSON.toJson(error(opId, e)));
            } catch (RuntimeException e) {
                LOG.error("[%s] start_profiling failed: %s", opId, e.getMessage(), e); //$NON-NLS-1$
                return ToolResult.failure(GSON.toJson(error(opId, "INTERNAL_ERROR", e.getMessage(), false))); //$NON-NLS-1$
            }
        });
    }

    private JsonObject error(String opId, ProfilingException e) {
        JsonObject error = error(opId, e.getCode(), e.getMessage(), e.isRecoverable());
        if (!e.getActiveTargets().isEmpty()) {
            error.add("activeTargets", GSON.toJsonTree(e.getActiveTargets())); //$NON-NLS-1$
        }
        return error;
    }

    private JsonObject error(String opId, String code, String message, boolean recoverable) {
        JsonObject error = new JsonObject();
        error.addProperty("opId", opId); //$NON-NLS-1$
        error.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        error.addProperty("error_code", code); //$NON-NLS-1$
        error.addProperty("message", message == null ? "Unknown profiling error" : message); //$NON-NLS-1$ //$NON-NLS-2$
        error.addProperty("recoverable", recoverable); //$NON-NLS-1$
        return error;
    }
}
