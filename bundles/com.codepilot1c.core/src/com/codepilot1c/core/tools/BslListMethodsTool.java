package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.lang.BslModuleMethodsRequest;
import com.codepilot1c.core.edt.lang.BslModuleMethodsResult;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Lists procedures/functions in a BSL module.
 */
public class BslListMethodsTool implements ITool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "filePath": {"type": "string", "description": "Path relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "name_contains": {"type": "string", "description": "Filter by method name substring"},
                "kind": {"type": "string", "enum": ["any", "procedure", "function"], "description": "Filter by method kind"},
                "limit": {"type": "integer", "description": "Max items (default 100)"},
                "offset": {"type": "integer", "description": "Pagination offset"}
              },
              "required": ["projectName", "filePath"]
            }
            """; //$NON-NLS-1$

    private final BslSemanticService service;

    public BslListMethodsTool() {
        this(new BslSemanticService());
    }

    BslListMethodsTool(BslSemanticService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "bsl_list_methods"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "List procedures/functions in a BSL module with line ranges and parameters."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BslModuleMethodsRequest request = BslModuleMethodsRequest.fromParameters(parameters);
                BslModuleMethodsResult result = service.listMethods(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (EdtAstException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("{\"error\":\"INTERNAL_ERROR\",\"message\":\"" //$NON-NLS-1$
                        + escapeJson(e.getMessage()) + "\"}"); //$NON-NLS-1$
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

    private String escapeJson(String text) {
        if (text == null) {
            return "unknown"; //$NON-NLS-1$
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
