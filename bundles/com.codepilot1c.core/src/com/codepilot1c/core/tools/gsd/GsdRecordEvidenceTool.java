/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.gsd.GsdContentRejectedException;
import com.codepilot1c.core.gsd.GsdProvenance;
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
 * Records a piece of evidence and links it to tasks.
 *
 * <p>Schema requires {@code project_path}, {@code expected_revision}, {@code id},
 * {@code description}, {@code provenance}. {@code task_ids} is optional.</p>
 */
@ToolMeta(
    name = "gsd_record_evidence",
    category = "gsd",
    mutating = true,
    tags = {"gsd", "verification"}
)
public class GsdRecordEvidenceTool extends AbstractTool {

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
                "id": {
                  "type": "string",
                  "description": "Stable evidence identifier."
                },
                "description": {
                  "type": "string",
                  "description": "What the evidence shows."
                },
                "provenance": {
                  "type": "string",
                  "enum": ["OBSERVED", "TESTED", "USER_ACCEPTED", "INFERRED"],
                  "description": "How the evidence was obtained."
                },
                "task_ids": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Tasks this evidence supports."
                }
              },
              "required": ["project_path", "expected_cycle_id", "expected_generation", "expected_revision", "id", "description", "provenance"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Records evidence backing a task or decision, with provenance (OBSERVED, TESTED, USER_ACCEPTED, INFERRED)."; //$NON-NLS-1$
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
                return GsdToolSupport.identityFailure("gsd_record_evidence", e); //$NON-NLS-1$
            } catch (ToolParameterException e) {
                return ToolResult.failure("Parameter error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Invalid parameter: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            } catch (GsdContentRejectedException e) {
                return ToolResult.failure("Content rejected: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_SECURITY)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleTokenException e) {
                return ToolResult.failure("Stale concurrency token", //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdStaleRevisionException e) {
                return ToolResult.failure("Stale revision: expected " + e.getExpectedRevision() //$NON-NLS-1$
                                + ", current " + e.getActualRevision(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_STALE)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdGuardException e) {
                return ToolResult.failure("Guard violation: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_GUARD)); //$NON-NLS-1$
            } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
                return ToolResult.failure("GSD state is corrupt: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_CORRUPT)); //$NON-NLS-1$
            } catch (IOException e) {
                return GsdToolSupport.ioFailure("gsd_record_evidence", "I/O error: ", e); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (RuntimeException e) {
                return ToolResult.failure("Internal error: " + e.getMessage(), //$NON-NLS-1$
                        GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
            }
        });
    }

    private ToolResult executeInternal(ToolParameters params, ToolExecutionContext context)
            throws IOException {
        GsdToolSupport.requireOnly(params, "project_path", "expected_cycle_id", //$NON-NLS-1$ //$NON-NLS-2$
                "expected_generation", "expected_revision", "id", "description", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "provenance", "task_ids"); //$NON-NLS-1$ //$NON-NLS-2$
        String projectPath = GsdToolSupport.requireProject(params, context);
        var expectedToken = GsdToolSupport.requireToken(params);
        String id = params.requireString("id"); //$NON-NLS-1$
        String description = params.requireString("description"); //$NON-NLS-1$
        String provenanceStr = params.requireString("provenance"); //$NON-NLS-1$
        GsdProvenance provenance = GsdProvenance.fromName(provenanceStr);
        if (provenance == null) {
            return ToolResult.failure("Unknown provenance: " + provenanceStr, //$NON-NLS-1$
                    GsdWorkflowService.buildResult(false, "gsd_record_evidence", 0, null, GsdWorkflowService.ERR_INVALID)); //$NON-NLS-1$
        }
        List<String> taskIds = GsdToolSupport.optionalStringList(params, "task_ids"); //$NON-NLS-1$

        GsdState state = GsdWorkflowService.recordEvidence(
                projectPath, expectedToken, id, description, provenance, taskIds);
        JsonObject structured = GsdToolSupport.stateEnvelope("gsd_record_evidence", state); //$NON-NLS-1$
        return ToolResult.success(
                "Evidence '" + id + "' recorded (provenance: " + provenance //$NON-NLS-1$ //$NON-NLS-2$
                        + "). Revision: " + state.revision(), structured); //$NON-NLS-1$
    }
}
