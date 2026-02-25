package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.lang.BslMethodBodyRequest;
import com.codepilot1c.core.edt.lang.BslMethodBodyResult;
import com.codepilot1c.core.edt.lang.BslMethodLookupException;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Fetches a BSL method body by name.
 */
public class BslGetMethodBodyTool implements ITool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "filePath": {"type": "string", "description": "Path relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "name": {"type": "string", "description": "Procedure/function name"},
                "kind": {"type": "string", "enum": ["any", "procedure", "function"], "description": "Filter by method kind"},
                "start_line": {"type": "integer", "description": "Disambiguation: method start line from candidates"},
                "context_lines": {"type": "integer", "description": "Extra lines before/after method"}
              },
              "required": ["projectName", "filePath", "name"]
            }
            """; //$NON-NLS-1$

    private final BslSemanticService service;

    public BslGetMethodBodyTool() {
        this(new BslSemanticService());
    }

    BslGetMethodBodyTool(BslSemanticService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "bsl_get_method_body"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Return the BSL procedure/function body by name with exact line range."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BslMethodBodyRequest request = BslMethodBodyRequest.fromParameters(parameters);
                BslMethodBodyResult result = service.getMethodBody(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CODE);
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
        if (e instanceof BslMethodLookupException lookup) {
            obj.add("candidates", GSON.toJsonTree(lookup.getCandidates())); //$NON-NLS-1$
        }
        return GSON.toJson(obj);
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "unknown"; //$NON-NLS-1$
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
