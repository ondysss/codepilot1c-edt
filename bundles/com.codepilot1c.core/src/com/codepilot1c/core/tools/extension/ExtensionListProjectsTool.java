package com.codepilot1c.core.tools.extension;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.extension.EdtExtensionService;
import com.codepilot1c.core.edt.extension.ExtensionListProjectsRequest;
import com.codepilot1c.core.edt.extension.ExtensionProjectsResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Lists EDT extension projects.
 */
@ToolMeta(name = "extension_list_projects", category = "extension", tags = {"read-only", "workspace", "edt"})
public class ExtensionListProjectsTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "base_project": {
                  "type": "string",
                  "description": "Filter by base (parent) project name"
                }
              }
            }
            """; //$NON-NLS-1$

    private final EdtExtensionService service;

    public ExtensionListProjectsTool() {
        this(new EdtExtensionService());
    }

    ExtensionListProjectsTool(EdtExtensionService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Lists EDT configuration extension projects with optional base-project filter."; //$NON-NLS-1$
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
                String baseProject = asOptionalString(parameters.get("base_project")); //$NON-NLS-1$
                ExtensionProjectsResult result = service.listProjects(new ExtensionListProjectsRequest(baseProject));
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (MetadataOperationException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
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

