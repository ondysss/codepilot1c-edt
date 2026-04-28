/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.debug;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

abstract class AbstractEdtDebugTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(AbstractEdtDebugTool.class);
    private static final Gson GSON = new Gson();

    @Override
    protected final CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId(getName().replace('_', '-'));
            try {
                LOG.info("[%s] START %s", opId, getName()); //$NON-NLS-1$
                Object result = executeDebug(params.getRaw());
                JsonObject structured = GSON.toJsonTree(result).getAsJsonObject();
                LOG.info("[%s] DONE %s", opId, getName()); //$NON-NLS-1$
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION, structured);
            } catch (EdtAstException e) {
                LOG.warn("[%s] %s failed: %s", opId, getName(), e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(errorJson(e.getCode().name(), e.getMessage(), e.isRecoverable()));
            } catch (Exception e) {
                LOG.error("[" + opId + "] " + getName() + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure(errorJson("INTERNAL_ERROR", e.getMessage(), false)); //$NON-NLS-1$
            }
        });
    }

    protected abstract Object executeDebug(Map<String, Object> parameters);

    private static String errorJson(String code, String message, boolean recoverable) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", code == null ? "" : code); //$NON-NLS-1$ //$NON-NLS-2$
        obj.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        obj.addProperty("recoverable", recoverable); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
