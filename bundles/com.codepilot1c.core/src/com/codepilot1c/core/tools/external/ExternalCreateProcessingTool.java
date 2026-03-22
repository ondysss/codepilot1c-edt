package com.codepilot1c.core.tools.external;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.external.EdtExternalObjectService;
import com.codepilot1c.core.edt.external.ExternalCreateObjectResult;
import com.codepilot1c.core.edt.external.ExternalCreateProcessingRequest;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.google.gson.Gson;

/**
 * Creates external data processor project and root external processing object.
 */
@ToolMeta(name = "external_create_processing", category = "external", mutating = true, tags = {"workspace", "edt"})
public class ExternalCreateProcessingTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Base EDT project name"
                },
                "external_project": {
                  "type": "string",
                  "description": "External-object project name to create"
                },
                "name": {
                  "type": "string",
                  "description": "External data processor object name"
                },
                "project_path": {
                  "type": "string",
                  "description": "Optional filesystem path for external project"
                },
                "version": {
                  "type": "string",
                  "description": "Platform version, defaults to base project version"
                },
                "synonym": {
                  "type": "string",
                  "description": "Optional synonym"
                },
                "comment": {
                  "type": "string",
                  "description": "Optional comment"
                },
                "validation_token": {
                  "type": "string",
                  "description": "One-time token from edt_validate_request"
                }
              },
              "required": ["project", "external_project", "name", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtExternalObjectService service;
    private final MetadataRequestValidationService validationService;

    public ExternalCreateProcessingTool() {
        this(new EdtExternalObjectService(), new MetadataRequestValidationService());
    }

    ExternalCreateProcessingTool(EdtExternalObjectService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Creates EDT external-object project with root ExternalDataProcessor object."; //$NON-NLS-1$
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
                String externalProject = asString(parameters.get("external_project")); //$NON-NLS-1$
                String name = asString(parameters.get("name")); //$NON-NLS-1$
                String projectPath = asOptionalString(parameters.get("project_path")); //$NON-NLS-1$
                String version = asOptionalString(parameters.get("version")); //$NON-NLS-1$
                String synonym = asOptionalString(parameters.get("synonym")); //$NON-NLS-1$
                String comment = asOptionalString(parameters.get("comment")); //$NON-NLS-1$
                String validationToken = asString(parameters.get("validation_token")); //$NON-NLS-1$

                validationService.normalizeExternalCreateProcessingPayload(
                        project,
                        externalProject,
                        name,
                        projectPath,
                        version,
                        synonym,
                        comment);

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.EXTERNAL_CREATE_PROCESSING,
                        project);

                ExternalCreateProcessingRequest request = new ExternalCreateProcessingRequest(
                        asString(validatedPayload.get("project")), //$NON-NLS-1$
                        asString(validatedPayload.get("external_project")), //$NON-NLS-1$
                        asString(validatedPayload.get("name")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("project_path")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("version")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("synonym")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("comment"))); //$NON-NLS-1$

                ExternalCreateObjectResult result = service.createProcessing(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("Ошибка external_create_processing: " + e.getMessage()); //$NON-NLS-1$
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
