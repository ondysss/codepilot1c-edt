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
import com.codepilot1c.core.gsd.GsdPhase;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Transitions the GSD phase. Forward transitions: DISCOVERY → PLANNING → EXECUTING →
 * VERIFYING → SHIPPING → CLOSED. Allowed rollbacks are VERIFYING → EXECUTING and
 * SHIPPING → VERIFYING/EXECUTING; every rollback requires {@code reason}.
 *
 * <p>Schema requires {@code project_path}, the complete concurrency token
 * ({@code expected_cycle_id}, {@code expected_generation}, and {@code expected_revision}),
 * and {@code target_phase}. {@code reason} is optional for forward transitions and
 * required for every rollback.</p>
 */
@ToolMeta(
    name = "gsd_transition",
    category = "gsd",
    mutating = true,
    tags = {"gsd", "lifecycle"}
)
public class GsdTransitionTool extends AbstractTool {

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
                "target_phase": {
                  "type": "string",
                  "enum": ["DISCOVERY", "PLANNING", "EXECUTING", "VERIFYING", "SHIPPING", "CLOSED"],
                  "description": "The phase to transition to."
                },
                "reason": {
                  "type": "string",
                  "description": "Required for every rollback from VERIFYING or SHIPPING."
                }
              },
              "required": ["project_path", "expected_cycle_id", "expected_generation", "expected_revision", "target_phase"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Transitions GSD through DISCOVERY->PLANNING->EXECUTING->VERIFYING->SHIPPING->CLOSED. " //$NON-NLS-1$
                + "Rollbacks from VERIFYING or SHIPPING require a reason.";
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
                return GsdToolSupport.identityFailure("gsd_transition", e); //$NON-NLS-1$
            } catch (ToolParameterException e) {
                return ToolResult.failure("Parameter error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Illegal transition: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (GsdContentRejectedException e) {
                return ToolResult.failure("Content rejected: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_SECURITY)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleTokenException e) {
                return ToolResult.failure("Stale concurrency token", //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleRevisionException e) {
                return ToolResult.failure("Stale revision: expected " + e.getExpectedRevision() //$NON-NLS-1$
                                + ", current " + e.getActualRevision(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdGuardException e) {
                return ToolResult.failure("Guard violation: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_GUARD)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
                return ToolResult.failure("GSD state is corrupt: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_CORRUPT)); //$NON-NLS-1$
            } catch (IOException e) {
                return ToolResult.failure("I/O error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_IO)); //$NON-NLS-1$
            } catch (RuntimeException e) {
                return ToolResult.failure("Internal error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            }
        });
    }

    private ToolResult executeInternal(ToolParameters params, ToolExecutionContext context)
            throws IOException {
        GsdToolSupport.requireOnly(params, "project_path", "expected_cycle_id", //$NON-NLS-1$ //$NON-NLS-2$
                "expected_generation", "expected_revision", "target_phase", "reason"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String projectPath = GsdToolSupport.requireProject(params, context);
        var expectedToken = GsdToolSupport.requireToken(params);
        String phaseStr = params.requireString("target_phase"); //$NON-NLS-1$
        GsdPhase targetPhase = GsdPhase.fromName(phaseStr);
        if (targetPhase == null) {
            return ToolResult.failure("Unknown phase: " + phaseStr, //$NON-NLS-1$
                    GsdWorkflowService.buildResult(false, "gsd_transition", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
        }
        Map<String, Object> raw = params.getRaw();
        String reason = raw.containsKey("reason") //$NON-NLS-1$
                ? GsdToolSupport.optionalString(params, "reason") : null; //$NON-NLS-1$

        GsdState state = GsdWorkflowService.transitionPhase(
                projectPath, expectedToken, targetPhase, reason);
        JsonObject structured = GsdToolSupport.stateEnvelope("gsd_transition", state); //$NON-NLS-1$
        return ToolResult.success(
                "Phase transitioned to " + state.phase() + ". Revision: " + state.revision(), //$NON-NLS-1$ //$NON-NLS-2$
                structured);
    }
}
