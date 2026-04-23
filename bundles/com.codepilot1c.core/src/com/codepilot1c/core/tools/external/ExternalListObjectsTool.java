package com.codepilot1c.core.tools.external;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.external.EdtExternalObjectService;
import com.codepilot1c.core.edt.external.ExternalListObjectsRequest;
import com.codepilot1c.core.edt.external.ExternalObjectsResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Lists external reports/processors in external object projects for a base configuration project.
 */
@ToolMeta(name = "external_list_objects", category = "external", tags = {"read-only", "workspace", "edt"})
public class ExternalListObjectsTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Base configuration project name"
                },
                "external_project": {
                  "type": "string",
                  "description": "Optional external-object project name filter"
                },
                "type_filter": {
                  "type": "string",
                  "description": "Kind filter token (e.g. ExternalReport, ExternalDataProcessor)"
                },
                "name_contains": {
                  "type": "string",
                  "description": "Case-insensitive object name filter"
                },
                "limit": {
                  "type": "integer",
                  "description": "Page size (1..1000, default 100)"
                },
                "offset": {
                  "type": "integer",
                  "description": "Pagination offset (default 0)"
                }
              },
              "required": ["project"]
            }
            """; //$NON-NLS-1$

    private final EdtExternalObjectService service;

    public ExternalListObjectsTool() {
        this(new EdtExternalObjectService());
    }

    ExternalListObjectsTool(EdtExternalObjectService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Lists external reports/processors for base EDT project with filters and pagination."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            try {
                ExternalListObjectsRequest request = new ExternalListObjectsRequest(
                        asString(parameters.get("project")), //$NON-NLS-1$
                        asOptionalString(parameters.get("external_project")), //$NON-NLS-1$
                        asOptionalString(parameters.get("type_filter")), //$NON-NLS-1$
                        asOptionalString(parameters.get("name_contains")), //$NON-NLS-1$
                        asOptionalInteger(parameters.get("limit")), //$NON-NLS-1$
                        asOptionalInteger(parameters.get("offset"))); //$NON-NLS-1$
                ExternalObjectsResult result = service.listObjects(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (MetadataOperationException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
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
        String raw = String.valueOf(value);
        return raw.isBlank() ? null : raw;
    }

    private Integer asOptionalInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        String raw = String.valueOf(value);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "Expected integer value, got: " + raw, false); //$NON-NLS-1$
        }
    }

    private String toErrorJson(MetadataOperationException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
