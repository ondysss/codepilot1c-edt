package com.codepilot1c.core.tools.external;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.external.EdtExternalObjectService;
import com.codepilot1c.core.edt.external.ExternalGetDetailsRequest;
import com.codepilot1c.core.edt.external.ExternalObjectDetailsResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Returns structural details for one external report/processor.
 */
@ToolMeta(name = "external_get_details", category = "external", tags = {"read-only", "workspace", "edt"})
public class ExternalGetDetailsTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Base configuration project name"
                },
                "object_fqn": {
                  "type": "string",
                  "description": "Object reference, e.g. ExternalReport.<Name>"
                },
                "external_project": {
                  "type": "string",
                  "description": "Optional external-object project name filter"
                }
              },
              "required": ["project", "object_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtExternalObjectService service;

    public ExternalGetDetailsTool() {
        this(new EdtExternalObjectService());
    }

    ExternalGetDetailsTool(EdtExternalObjectService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Returns detailed structure of external report/processor object in project scope."; //$NON-NLS-1$
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
                ExternalGetDetailsRequest request = new ExternalGetDetailsRequest(
                        asString(parameters.get("project")), //$NON-NLS-1$
                        asString(parameters.get("object_fqn")), //$NON-NLS-1$
                        asOptionalString(parameters.get("external_project"))); //$NON-NLS-1$
                ExternalObjectDetailsResult result = service.getDetails(request);
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

    private String toErrorJson(MetadataOperationException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
