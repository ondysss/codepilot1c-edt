package com.codepilot1c.core.tools.bsl;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.lang.BslScopeMembersRequest;
import com.codepilot1c.core.edt.lang.BslScopeMembersResult;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Returns BSL scope members at source position.
 */
@ToolMeta(name = "bsl_scope_members", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class BslScopeMembersTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project containing the BSL module"},
                "filePath": {"type": "string", "description": "Path to the module relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "line": {"type": "integer", "description": "1-based line of the expression or cursor position"},
                "column": {"type": "integer", "description": "1-based column of the expression or cursor position"},
                "limit": {"type": "integer", "description": "Maximum scope members to return at this position (default 50)"},
                "offset": {"type": "integer", "description": "Pagination offset for long member lists"},
                "contains": {"type": "string", "description": "Optional name filter to narrow large scope member lists"},
                "language": {"type": "string", "enum": ["ru", "en"], "description": "Preferred language for member and type names"}
              },
              "required": ["projectName", "filePath", "line", "column"]
            }
            """; //$NON-NLS-1$

    private final BslSemanticService service;

    public BslScopeMembersTool() {
        this(new BslSemanticService());
    }

    BslScopeMembersTool(BslSemanticService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Показывает методы, свойства и доступные элементы в текущей области видимости BSL."; //$NON-NLS-1$
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
                BslScopeMembersRequest request = BslScopeMembersRequest.fromParameters(parameters);
                BslScopeMembersResult result = service.getScopeMembers(request);
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
