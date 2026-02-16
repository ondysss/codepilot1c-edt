package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.codepilot1c.core.edt.ast.MetadataIndexRequest;
import com.codepilot1c.core.edt.ast.MetadataIndexResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Scans top-level metadata objects in EDT configuration.
 */
public class ScanMetadataIndexTool implements ITool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "scope": {"type": "string", "description": "Metadata scope filter: all/catalogs/documents/commonModules/..."},
                "nameContains": {"type": "string", "description": "Case-insensitive object name filter"},
                "limit": {"type": "integer", "description": "Maximum result size (1..1000, default 200)"},
                "language": {"type": "string", "description": "Synonym language (ru/en/...)"},
                "includeModules": {"type": "boolean", "description": "Reserved for future filtering (compat parameter)"}
              },
              "required": ["projectName"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "scan_metadata_index"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Collects top-level metadata index from EDT configuration with scope and name filters."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MetadataIndexRequest request = MetadataIndexRequest.fromParameters(parameters);
                MetadataIndexResult result = EdtAstServices.getInstance().scanMetadataIndex(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (EdtAstException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String toErrorJson(EdtAstException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
