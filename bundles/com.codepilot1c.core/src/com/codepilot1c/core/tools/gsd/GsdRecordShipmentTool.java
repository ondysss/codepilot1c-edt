/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.gsd.GsdCommitOutcome;
import com.codepilot1c.core.gsd.GsdContentRejectedException;
import com.codepilot1c.core.gsd.GsdShipment;
import com.codepilot1c.core.gsd.GsdShipmentConflictException;
import com.codepilot1c.core.gsd.GsdShipmentStatus;
import com.codepilot1c.core.gsd.GsdWorkflowService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolParameters.ToolParameterException;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/** Records one conflict-safe shipment result for the current SHIPPING cycle. */
@ToolMeta(name = "gsd_record_shipment", category = "gsd", mutating = true,
        tags = {"gsd", "shipment"})
public final class GsdRecordShipmentTool extends AbstractTool {

    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_path": {"type": "string"},
                "expected_cycle_id": {"type": "string"},
                "expected_generation": {"type": "integer"},
                "expected_revision": {"type": "integer"},
                "shipment_id": {"type": "string"},
                "delivery_reference": {"type": "string"},
                "status": {"type": "string", "enum": ["IN_PROGRESS", "COMPLETED", "FAILED"]},
                "completed_at": {"type": "string", "format": "date-time"}
              },
              "required": ["project_path", "expected_cycle_id", "expected_generation",
                "expected_revision", "shipment_id", "delivery_reference", "status"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Records the current cycle shipment. COMPLETED requires completed_at; exact retries with the current token are idempotent and conflicting records fail."; //$NON-NLS-1$
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
        final String operation = "gsd_record_shipment"; //$NON-NLS-1$
        try {
            GsdToolSupport.requireOnly(params, "project_path", "expected_cycle_id", //$NON-NLS-1$ //$NON-NLS-2$
                    "expected_generation", "expected_revision", "shipment_id", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "delivery_reference", "status", "completed_at"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String projectPath = GsdToolSupport.requireProject(params, context);
            var token = GsdToolSupport.requireToken(params);
            String shipmentId = params.requireString("shipment_id"); //$NON-NLS-1$
            String reference = params.requireString("delivery_reference"); //$NON-NLS-1$
            GsdShipmentStatus status;
            try {
                status = GsdShipmentStatus.valueOf(params.requireString("status")); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                throw new ToolParameterException(
                        "status must be IN_PROGRESS, COMPLETED, or FAILED"); //$NON-NLS-1$
            }
            Instant completedAt = null;
            String completedText = GsdToolSupport.optionalString(params, "completed_at"); //$NON-NLS-1$
            if (status == GsdShipmentStatus.COMPLETED) {
                if (completedText == null || completedText.isBlank()) {
                    throw new ToolParameterException(
                            "completed_at is required when status is COMPLETED"); //$NON-NLS-1$
                }
                try {
                    completedAt = Instant.parse(completedText);
                } catch (DateTimeParseException e) {
                    throw new ToolParameterException("completed_at must be an RFC 3339 instant"); //$NON-NLS-1$
                }
            } else if (completedText != null) {
                throw new ToolParameterException(
                        "completed_at is allowed only when status is COMPLETED"); //$NON-NLS-1$
            }
            GsdShipment shipment = new GsdShipment(
                    shipmentId, reference, status, completedAt);
            GsdCommitOutcome outcome = GsdWorkflowService.recordShipmentWithOutcome(
                    projectPath, token, shipment);
            JsonObject payload = GsdToolSupport.stateEnvelope(operation, outcome.state());
            payload.addProperty("idempotent", !outcome.committed()); //$NON-NLS-1$
            GsdToolSupport.addWarnings(payload, outcome.warnings());
            return ToolResult.success(outcome.committed()
                    ? "Shipment recorded" : "Shipment already recorded; no state change", payload); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (GsdToolSupport.GsdToolIdentityException e) {
            return GsdToolSupport.identityFailure(operation, e);
        } catch (GsdShipmentConflictException e) {
            return failure(operation, GsdWorkflowService.ERR_CONFLICT, e.getMessage());
        } catch (com.codepilot1c.core.gsd.GsdStaleTokenException e) {
            return failure(operation, GsdWorkflowService.ERR_STALE, "stale concurrency token"); //$NON-NLS-1$
        } catch (com.codepilot1c.core.gsd.GsdStaleRevisionException e) {
            return failure(operation, GsdWorkflowService.ERR_STALE, "stale concurrency token"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            return failure(operation, GsdWorkflowService.ERR_INVALID, e.getMessage());
        } catch (ToolParameterException | IllegalArgumentException e) {
            return failure(operation, GsdWorkflowService.ERR_INVALID, e.getMessage());
        } catch (GsdContentRejectedException e) {
            return failure(operation, GsdWorkflowService.ERR_SECURITY, e.getMessage());
        } catch (com.codepilot1c.core.gsd.GsdGuardException e) {
            return failure(operation, GsdWorkflowService.ERR_GUARD, e.getMessage());
        } catch (com.codepilot1c.core.gsd.GsdCorruptException e) {
            return failure(operation, GsdWorkflowService.ERR_CORRUPT, e.getMessage());
        } catch (IOException e) {
            return failure(operation, GsdWorkflowService.ERR_IO, e.getMessage());
        } catch (RuntimeException e) {
            return failure(operation, GsdWorkflowService.ERR_INVALID,
                    "Unexpected runtime failure while recording shipment"); //$NON-NLS-1$
        }
    }

    private ToolResult failure(String operation, String code, String message) {
        return ToolResult.failure(message, GsdWorkflowService.buildResult(
                false, operation, 0, null, code));
    }
}
