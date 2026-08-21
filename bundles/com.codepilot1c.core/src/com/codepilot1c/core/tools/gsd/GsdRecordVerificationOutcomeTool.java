/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.gsd.GsdAcceptanceStatus;
import com.codepilot1c.core.gsd.GsdContentRejectedException;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;

/** Records the PASS/FAIL outcome for one persisted acceptance criterion. */
@ToolMeta(name = "gsd_record_verification_outcome", category = "gsd", mutating = true,
        tags = {"gsd", "verification"})
public final class GsdRecordVerificationOutcomeTool extends AbstractTool {

    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_path": {"type": "string"},
                "expected_cycle_id": {"type": "string"},
                "expected_generation": {"type": "integer"},
                "expected_revision": {"type": "integer"},
                "criterion_id": {"type": "string"},
                "outcome": {"type": "string", "enum": ["PASSED", "FAILED"]}
              },
              "required": ["project_path", "expected_cycle_id", "expected_generation",
                "expected_revision", "criterion_id", "outcome"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Records a PASSED or FAILED verification outcome for one GSD acceptance criterion."; //$NON-NLS-1$
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
        return CompletableFuture.supplyAsync(() -> executeInternal(params, context));
    }

    private ToolResult executeInternal(ToolParameters params, ToolExecutionContext context) {
        final String operation = "gsd_record_verification_outcome"; //$NON-NLS-1$
        try {
            GsdToolSupport.requireOnly(params, "project_path", "expected_cycle_id", //$NON-NLS-1$ //$NON-NLS-2$
                    "expected_generation", "expected_revision", "criterion_id", "outcome"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            String projectPath = GsdToolSupport.requireProject(params, context);
            var token = GsdToolSupport.requireToken(params);
            String criterionId = params.requireString("criterion_id"); //$NON-NLS-1$
            String requested = params.requireString("outcome"); //$NON-NLS-1$
            GsdAcceptanceStatus outcome;
            try {
                outcome = GsdAcceptanceStatus.valueOf(requested);
            } catch (IllegalArgumentException e) {
                throw new ToolParameterException("outcome must be PASSED or FAILED"); //$NON-NLS-1$
            }
            GsdState state = GsdWorkflowService.updateAcceptanceCriterion(
                    projectPath, token, criterionId, outcome);
            return ToolResult.success(
                    "Verification outcome recorded for '" + criterionId + "': " + outcome, //$NON-NLS-1$ //$NON-NLS-2$
                    GsdToolSupport.stateEnvelope(operation, state));
        } catch (GsdToolSupport.GsdToolIdentityException e) {
            return GsdToolSupport.identityFailure(operation, e);
        } catch (ToolParameterException | IllegalArgumentException e) {
            return failure(operation, GsdWorkflowService.ERR_INVALID, e.getMessage());
        } catch (com.codepilot1c.core.gsd.GsdStaleTokenException e) {
            return failure(operation, GsdWorkflowService.ERR_STALE, "stale concurrency token"); //$NON-NLS-1$
        } catch (GsdContentRejectedException e) {
            return failure(operation, GsdWorkflowService.ERR_SECURITY, e.getMessage());
        } catch (com.codepilot1c.core.gsd.GsdGuardException e) {
            return failure(operation, GsdWorkflowService.ERR_GUARD, e.getMessage());
        } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
            return failure(operation, GsdWorkflowService.ERR_CORRUPT, e.getMessage());
        } catch (IOException e) {
            return failure(operation, GsdWorkflowService.ERR_IO, e.getMessage());
        }
    }

    private ToolResult failure(String operation, String code, String message) {
        return ToolResult.failure(message, GsdWorkflowService.buildResult(
                false, operation, 0, null, code));
    }
}
