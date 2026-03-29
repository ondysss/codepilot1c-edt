package com.codepilot1c.core.tools.bsl;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.ContentAssistRequest;
import com.codepilot1c.core.edt.ast.ContentAssistResult;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Internal EDT AST content-assist tool.
 */
@ToolMeta(name = "edt_content_assist", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class EdtContentAssistTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project containing the BSL module"},
                "filePath": {"type": "string", "description": "Path to the module relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "line": {"type": "integer", "description": "1-based line where completion is requested"},
                "column": {"type": "integer", "description": "1-based column where completion is requested"},
                "limit": {"type": "integer", "description": "Maximum completion proposals to return (default 20)"},
                "offset": {"type": "integer", "description": "Pagination offset for long proposal lists"},
                "contains": {"type": "string", "description": "Optional comma-separated text filters for proposal names"},
                "extendedDocumentation": {"type": "boolean", "description": "Include extended documentation for proposals when available"}
              },
              "required": ["projectName", "filePath", "line", "column"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Возвращает EDT content assist для позиции в BSL. Используй, когда нужны варианты автодополнения."; //$NON-NLS-1$
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
                ContentAssistRequest request = ContentAssistRequest.fromParameters(parameters);
                ContentAssistResult result = EdtAstServices.getInstance().getContentAssist(request);
                return ToolResult.success(GSON.toJson(result));
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
