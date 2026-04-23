package com.codepilot1c.core.tools.bsl;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

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
@ToolMeta(name = "bsl_get_method_body", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class BslGetMethodBodyTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project containing the BSL module"},
                "filePath": {"type": "string", "description": "Path to the module relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "name": {"type": "string", "description": "Exact procedure/function name to extract; use bsl_list_methods first if unknown"},
                "kind": {"type": "string", "enum": ["any", "procedure", "function"], "description": "Optional method kind filter when the name is ambiguous"},
                "start_line": {"type": "integer", "description": "Optional exact start line when several methods share the same name"},
                "context_lines": {"type": "integer", "description": "Extra lines before and after the method body; not a whole-file read"}
              },
              "required": ["projectName", "filePath", "name"]
            }
            """; //$NON-NLS-1$

    private final BslSemanticService service;

    public BslGetMethodBodyTool() {
        this(new BslSemanticService());
    }

    public BslGetMethodBodyTool(BslSemanticService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Возвращает тело конкретной процедуры или функции BSL с точным диапазоном строк."; //$NON-NLS-1$
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
