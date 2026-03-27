package com.codepilot1c.core.tools.bsl;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.lang.BslModuleExportsResult;
import com.codepilot1c.core.edt.lang.BslModuleMethodsRequest;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Lists exported procedures/functions in a BSL module.
 */
@ToolMeta(name = "bsl_module_exports", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class BslModuleExportsTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project containing the BSL module"},
                "filePath": {"type": "string", "description": "Path to the module relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "name_contains": {"type": "string", "description": "Optional substring filter for exported procedure/function names"},
                "limit": {"type": "integer", "description": "Maximum exported members to return (default 100)"},
                "offset": {"type": "integer", "description": "Pagination offset for large export lists"}
              },
              "required": ["projectName", "filePath"]
            }
            """; //$NON-NLS-1$

    private final BslSemanticService service;

    public BslModuleExportsTool() {
        this(new BslSemanticService());
    }

    public BslModuleExportsTool(BslSemanticService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Перечисляет только экспортные процедуры и функции одного BSL-модуля."; //$NON-NLS-1$
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
                BslModuleMethodsRequest request = BslModuleMethodsRequest.fromParameters(parameters);
                BslModuleExportsResult result = service.getModuleExports(request);
                JsonObject structured = GSON.toJsonTree(result).getAsJsonObject();
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS, structured);
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
