package com.codepilot1c.core.tools.extension;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.extension.EdtExtensionService;
import com.codepilot1c.core.edt.extension.ExtensionListObjectsRequest;
import com.codepilot1c.core.edt.extension.ExtensionObjectsResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Lists extension configuration objects with filters and pagination.
 */
@ToolMeta(name = "extension_list_objects", category = "extension", tags = {"read-only", "workspace", "edt"})
public class ExtensionListObjectsTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "extension_project": {
                  "type": "string",
                  "description": "Extension project name"
                },
                "type_filter": {
                  "type": "string",
                  "description": "Filter by type/kind token (e.g. CatalogExtension, documents)"
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
              "required": ["extension_project"]
            }
            """; //$NON-NLS-1$

    private final EdtExtensionService service;

    public ExtensionListObjectsTool() {
        this(new EdtExtensionService());
    }

    ExtensionListObjectsTool(EdtExtensionService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Lists metadata objects contained in EDT extension project configuration."; //$NON-NLS-1$
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
                ExtensionListObjectsRequest request = new ExtensionListObjectsRequest(
                        asString(parameters.get("extension_project")), //$NON-NLS-1$
                        asOptionalString(parameters.get("type_filter")), //$NON-NLS-1$
                        asOptionalString(parameters.get("name_contains")), //$NON-NLS-1$
                        asOptionalInteger(parameters.get("limit")), //$NON-NLS-1$
                        asOptionalInteger(parameters.get("offset"))); //$NON-NLS-1$
                ExtensionObjectsResult result = service.listObjects(request);
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
                    com.codepilot1c.core.edt.metadata.MetadataOperationCode.KNOWLEDGE_REQUIRED,
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

