/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Polls the status of a background {@code edt_update_infobase} job
 * started via {@code async=true}.
 */
@ToolMeta(
        name = "update_infobase_status",
        category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery",
        mutating = false,
        tags = {"workspace", "edt"})
public class UpdateInfobaseStatusTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG =
            VibeLogger.forClass(UpdateInfobaseStatusTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "job_id": {
                  "type": "string",
                  "description": "Job id returned by edt_update_infobase when async=true"
                }
              },
              "required": ["job_id"]
            }
            """; //$NON-NLS-1$

    private final BackgroundJobRegistry registry;

    public UpdateInfobaseStatusTool() {
        this(BackgroundJobRegistry.getInstance());
    }

    UpdateInfobaseStatusTool(BackgroundJobRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getDescription() {
        return "Опрашивает статус фонового обновления инфобазы EDT: state, время запуска/завершения, результат или ошибка."; //$NON-NLS-1$
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
        Map<String, Object> parameters = params.getRaw();
        String jobId = asString(parameters == null ? null : parameters.get("job_id")); //$NON-NLS-1$
        if (jobId == null || jobId.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("job_id is required")); //$NON-NLS-1$
        }
        BackgroundJobRegistry.JobLookup lookup = registry.lookupJob(jobId);
        switch (lookup.getKind()) {
            case PRESENT: {
                JsonObject payload = render(lookup.getStatus());
                return CompletableFuture.completedFuture(
                        ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE));
            }
            case EXPIRED: {
                LOG.warn("update_infobase_status: expired job id %s", jobId); //$NON-NLS-1$
                JsonObject payload = new JsonObject();
                payload.addProperty("job_id", jobId); //$NON-NLS-1$
                payload.addProperty("error", "job_expired"); //$NON-NLS-1$ //$NON-NLS-2$
                if (lookup.getExpiredAt() != null) {
                    payload.addProperty("expired_at", lookup.getExpiredAt().toString()); //$NON-NLS-1$
                }
                payload.addProperty("message", //$NON-NLS-1$
                        "Job result retention window has elapsed"); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.failure(pretty(payload)));
            }
            case UNKNOWN:
            default: {
                LOG.warn("update_infobase_status: unknown job id %s", jobId); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.failure("Unknown job: " + jobId)); //$NON-NLS-1$
            }
        }
    }

    private static JsonObject render(BackgroundJobRegistry.JobStatus status) {
        JsonObject out = new JsonObject();
        out.addProperty("job_id", status.getJobId()); //$NON-NLS-1$
        out.addProperty("kind", status.getKind()); //$NON-NLS-1$
        out.addProperty("state", status.getState().name()); //$NON-NLS-1$
        addInstant(out, "submittedAt", status.getSubmittedAt()); //$NON-NLS-1$
        addInstant(out, "startedAt", status.getStartedAt()); //$NON-NLS-1$
        addInstant(out, "finishedAt", status.getFinishedAt()); //$NON-NLS-1$
        if (status.getState() == BackgroundJobRegistry.JobState.DONE && status.getResult() != null) {
            out.addProperty("result", status.getResult()); //$NON-NLS-1$
        }
        if (status.getState() == BackgroundJobRegistry.JobState.FAILED && status.getError() != null) {
            out.addProperty("error", status.getError()); //$NON-NLS-1$
        }
        return out;
    }

    private static void addInstant(JsonObject out, String key, Instant instant) {
        if (instant != null) {
            out.addProperty(key, instant.toString());
        }
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
