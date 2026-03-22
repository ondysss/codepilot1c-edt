package com.codepilot1c.core.tools.dcs;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.dcs.DcsCreateMainSchemaRequest;
import com.codepilot1c.core.edt.dcs.DcsCreateMainSchemaResult;
import com.codepilot1c.core.edt.dcs.EdtDcsService;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.google.gson.Gson;

/**
 * Creates and binds DCS main schema for owner object.
 */
@ToolMeta(name = "dcs_create_main_schema", category = "dcs", mutating = true, tags = {"workspace", "edt"})
public class DcsCreateMainSchemaTool extends AbstractTool {

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
                "template_name": {
                  "type": "string",
                  "description": "Optional DCS template name (default MainDataCompositionSchema)"
                },
                "force_replace": {
                  "type": "boolean",
                  "description": "Replace binding if schema already exists"
                },
                "validation_token": {
                  "type": "string",
                  "description": "One-time token from edt_validate_request"
                }
              },
              "required": ["project", "owner_fqn", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtDcsService service;
    private final MetadataRequestValidationService validationService;

    public DcsCreateMainSchemaTool() {
        this(new EdtDcsService(), new MetadataRequestValidationService());
    }

    DcsCreateMainSchemaTool(EdtDcsService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Creates main DCS schema and binds it to owner object."; //$NON-NLS-1$
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
                String templateName = asOptionalString(parameters.get("template_name")); //$NON-NLS-1$
                Boolean forceReplace = asOptionalBoolean(parameters.get("force_replace")); //$NON-NLS-1$
                String validationToken = asString(parameters.get("validation_token")); //$NON-NLS-1$

                validationService.normalizeDcsCreateMainSchemaPayload(
                        project,
                        ownerFqn,
                        templateName,
                        forceReplace);

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.DCS_CREATE_MAIN_SCHEMA,
                        project);

                DcsCreateMainSchemaRequest request = new DcsCreateMainSchemaRequest(
                        asString(validatedPayload.get("project")), //$NON-NLS-1$
                        asString(validatedPayload.get("owner_fqn")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("template_name")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("force_replace"))); //$NON-NLS-1$
                DcsCreateMainSchemaResult result = service.createMainSchema(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("Ошибка dcs_create_main_schema: " + e.getMessage()); //$NON-NLS-1$
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
