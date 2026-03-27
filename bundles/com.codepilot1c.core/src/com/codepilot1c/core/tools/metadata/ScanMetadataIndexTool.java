package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

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
@ToolMeta(name = "scan_metadata_index", category = "metadata", tags = {"read-only", "workspace", "edt"})
public class ScanMetadataIndexTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project whose configuration should be scanned"},
                "scope": {"type": "string", "description": "High-level metadata scope filter such as all, catalogs, documents, commonModules"},
                "nameContains": {"type": "string", "description": "Case-insensitive object name filter for broad discovery"},
                "limit": {"type": "integer", "description": "Maximum number of index entries to return (1..1000, default 200)"},
                "language": {"type": "string", "description": "Preferred synonym language for display values (ru, en, ...)"},
                "includeModules": {"type": "boolean", "description": "Compatibility flag; does not replace detailed module inspection"}
              },
              "required": ["projectName"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Возвращает индекс верхнеуровневых объектов метаданных EDT по проекту с широкими фильтрами."; //$NON-NLS-1$
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
                MetadataIndexRequest request = MetadataIndexRequest.fromParameters(parameters);
                MetadataIndexResult result = EdtAstServices.getInstance().scanMetadataIndex(request);
                JsonObject structured = GSON.toJsonTree(result).getAsJsonObject();
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS, structured);
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
