package com.codepilot1c.core.tools.dcs;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.dcs.DcsUpsertCalculatedFieldRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertCalculatedFieldResult;
import com.codepilot1c.core.edt.dcs.EdtDcsService;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.google.gson.Gson;

/**
 * Creates or updates DCS calculated field in owner schema.
 */
@ToolMeta(name = "dcs_upsert_calculated_field", category = "dcs", mutating = true, tags = {"workspace", "edt"})
public class DcsUpsertCalculatedFieldTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "EDT project name (base or external)"
                },
                "owner_fqn": {
                  "type": "string",
                  "description": "Owner FQN, e.g. Report.Sales or ExternalReport.MyReport"
                },
                "data_path": {
                  "type": "string",
                  "description": "Calculated field data path"
                },
                "expression": {
                  "type": "string",
                  "description": "Optional calculated expression"
                },
                "presentation_expression": {
                  "type": "string",
                  "description": "Optional presentation expression"
                },
                "validation_token": {
                  "type": "string",
                  "description": "One-time token from edt_validate_request"
                }
              },
              "required": ["project", "owner_fqn", "data_path", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtDcsService service;
    private final MetadataRequestValidationService validationService;

    public DcsUpsertCalculatedFieldTool() {
        this(new EdtDcsService(), new MetadataRequestValidationService());
    }

    DcsUpsertCalculatedFieldTool(EdtDcsService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Creates or updates DCS calculated field for owner object."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            try {
                String project = asString(parameters.get("project")); //$NON-NLS-1$
                String ownerFqn = asString(parameters.get("owner_fqn")); //$NON-NLS-1$
                String dataPath = asString(parameters.get("data_path")); //$NON-NLS-1$
                String expression = asOptionalString(parameters.get("expression")); //$NON-NLS-1$
                String presentationExpression = asOptionalString(parameters.get("presentation_expression")); //$NON-NLS-1$
                String validationToken = asString(parameters.get("validation_token")); //$NON-NLS-1$

                validationService.normalizeDcsUpsertCalculatedFieldPayload(
                        project,
                        ownerFqn,
                        dataPath,
                        expression,
                        presentationExpression);

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.DCS_UPSERT_CALCULATED_FIELD,
                        project);

                DcsUpsertCalculatedFieldRequest request = new DcsUpsertCalculatedFieldRequest(
                        asString(validatedPayload.get("project")), //$NON-NLS-1$
                        asString(validatedPayload.get("owner_fqn")), //$NON-NLS-1$
                        asString(validatedPayload.get("data_path")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("expression")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("presentation_expression"))); //$NON-NLS-1$
                DcsUpsertCalculatedFieldResult result = service.upsertCalculatedField(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("Ошибка dcs_upsert_calculated_field: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asOptionalString(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }
}
