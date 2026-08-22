/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.gsd.GsdContentRejectedException;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdTaskStatus;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Updates a single task's status.
 *
 * <p>Schema requires {@code project_path}, {@code expected_revision}, {@code task_id},
 * and {@code status}. No other mutable fields are exposed — plan changes go through
 * {@code gsd_create_plan} (PLANNING phase), evidence links through
 * {@code gsd_record_evidence}.</p>
 */
@ToolMeta(
    name = "gsd_update_task",
    category = "gsd",
    mutating = true,
    tags = {"gsd", "execution"}
)
public class GsdUpdateTaskTool extends AbstractTool {

    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_path": {
                  "type": "string",
                  "description": "Absolute path to the project root."
                },
                "expected_cycle_id": {"type": "string"},
                "expected_generation": {"type": "integer"},
                "expected_revision": {
                  "type": "integer",
                  "description": "Expected revision for optimistic concurrency."
                },
                "task_id": {
                  "type": "string",
                  "description": "The task id to update."
                },
                "status": {
                  "type": "string",
                  "enum": ["PENDING", "IN_PROGRESS", "BLOCKED", "DONE"],
                  "description": "New task status."
                }
              },
              "required": ["project_path", "expected_cycle_id", "expected_generation", "expected_revision", "task_id", "status"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Updates a single GSD task's status. Plan changes use gsd_create_plan; " //$NON-NLS-1$
                + "evidence links use gsd_record_evidence.";
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
            try {
                return executeInternal(params, context);
            } catch (GsdToolSupport.GsdToolIdentityException e) {
                return GsdToolSupport.identityFailure("gsd_update_task", e); //$NON-NLS-1$
            } catch (ToolParameterException e) {
                return ToolResult.failure("Parameter error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Invalid: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (GsdContentRejectedException e) {
                return ToolResult.failure("Content rejected: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_SECURITY)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleTokenException e) {
                return ToolResult.failure("Stale concurrency token", //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleRevisionException e) {
                return ToolResult.failure("Stale revision: expected " + e.getExpectedRevision() //$NON-NLS-1$
                                + ", current " + e.getActualRevision(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdGuardException e) {
                return ToolResult.failure("Guard violation: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_GUARD)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
                return ToolResult.failure("GSD state is corrupt: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_CORRUPT)); //$NON-NLS-1$
            } catch (IOException e) {
                return GsdToolSupport.ioFailure("gsd_update_task", "I/O error: ", e); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (RuntimeException e) {
                return ToolResult.failure("Internal error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            }
        });
    }

    private ToolResult executeInternal(ToolParameters params, ToolExecutionContext context)
            throws IOException {
        GsdToolSupport.requireOnly(params, "project_path", "expected_cycle_id", //$NON-NLS-1$ //$NON-NLS-2$
                "expected_generation", "expected_revision", "task_id", "status"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String projectPath = GsdToolSupport.requireProject(params, context);
        var expectedToken = GsdToolSupport.requireToken(params);
        String taskId = params.requireString("task_id"); //$NON-NLS-1$
        String statusStr = params.requireString("status"); //$NON-NLS-1$
        GsdTaskStatus newStatus = GsdTaskStatus.fromName(statusStr);
        if (newStatus == null) {
            return ToolResult.failure("Unknown status: " + statusStr //$NON-NLS-1$
                    + "; must be PENDING, IN_PROGRESS, BLOCKED, or DONE", //$NON-NLS-1$
                    GsdWorkflowService.buildResult(false, "gsd_update_task", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
        }

        GsdState state = GsdWorkflowService.updateTask(
                projectPath, expectedToken, taskId, newStatus);
        JsonObject structured = GsdToolSupport.stateEnvelope("gsd_update_task", state); //$NON-NLS-1$
        return ToolResult.success(
                "Task '" + taskId + "' updated to " + newStatus + ". Revision: " + state.revision(), structured); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
