package com.codepilot1c.core.tools.dcs;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.dcs.DcsUpsertParameterRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertParameterResult;
import com.codepilot1c.core.edt.dcs.EdtDcsService;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.google.gson.Gson;

/**
 * Creates or updates DCS parameter in owner schema.
 */
@ToolMeta(name = "dcs_upsert_parameter", category = "dcs", mutating = true, tags = {"workspace", "edt"})
public class DcsUpsertParameterTool extends AbstractTool {

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
                "parameter_name": {
                  "type": "string",
                  "description": "Parameter name"
                },
                "expression": {
                  "type": "string",
                  "description": "Optional expression"
                },
                "available_as_field": {
                  "type": "boolean",
                  "description": "Optional available as field flag"
                },
                "value_list_allowed": {
                  "type": "boolean",
                  "description": "Optional value list allowed flag"
                },
                "deny_incomplete_values": {
                  "type": "boolean",
                  "description": "Optional deny incomplete values flag"
                },
                "use_restriction": {
                  "type": "boolean",
                  "description": "Optional use restriction flag"
                },
                "validation_token": {
                  "type": "string",
                  "description": "One-time token from edt_validate_request"
                }
              },
              "required": ["project", "owner_fqn", "parameter_name", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtDcsService service;
    private final MetadataRequestValidationService validationService;

    public DcsUpsertParameterTool() {
        this(new EdtDcsService(), new MetadataRequestValidationService());
    }

    DcsUpsertParameterTool(EdtDcsService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Creates or updates DCS parameter for owner object."; //$NON-NLS-1$
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
                String parameterName = asString(parameters.get("parameter_name")); //$NON-NLS-1$
                String expression = asOptionalString(parameters.get("expression")); //$NON-NLS-1$
                Boolean availableAsField = asOptionalBoolean(parameters.get("available_as_field")); //$NON-NLS-1$
                Boolean valueListAllowed = asOptionalBoolean(parameters.get("value_list_allowed")); //$NON-NLS-1$
                Boolean denyIncompleteValues = asOptionalBoolean(parameters.get("deny_incomplete_values")); //$NON-NLS-1$
                Boolean useRestriction = asOptionalBoolean(parameters.get("use_restriction")); //$NON-NLS-1$
                String validationToken = asString(parameters.get("validation_token")); //$NON-NLS-1$

                validationService.normalizeDcsUpsertParameterPayload(
                        project,
                        ownerFqn,
                        parameterName,
                        expression,
                        availableAsField,
                        valueListAllowed,
                        denyIncompleteValues,
                        useRestriction);

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.DCS_UPSERT_PARAMETER,
                        project);

                DcsUpsertParameterRequest request = new DcsUpsertParameterRequest(
                        asString(validatedPayload.get("project")), //$NON-NLS-1$
                        asString(validatedPayload.get("owner_fqn")), //$NON-NLS-1$
                        asString(validatedPayload.get("parameter_name")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("expression")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("available_as_field")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("value_list_allowed")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("deny_incomplete_values")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("use_restriction"))); //$NON-NLS-1$
                DcsUpsertParameterResult result = service.upsertParameter(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("Ошибка dcs_upsert_parameter: " + e.getMessage()); //$NON-NLS-1$
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

    private Boolean asOptionalBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        if ("1".equals(raw)) { //$NON-NLS-1$
            return Boolean.TRUE;
        }
        if ("0".equals(raw)) { //$NON-NLS-1$
            return Boolean.FALSE;
        }
        return Boolean.valueOf(Boolean.parseBoolean(raw));
    }
}
