package com.codepilot1c.core.tools;

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
public class BslScopeMembersTool implements ITool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "filePath": {"type": "string", "description": "Path relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "line": {"type": "integer", "description": "1-based line"},
                "column": {"type": "integer", "description": "1-based column"},
                "limit": {"type": "integer", "description": "Max scope members (default 50)"},
                "offset": {"type": "integer", "description": "Pagination offset"},
                "contains": {"type": "string", "description": "Contains filter for member name"},
                "language": {"type": "string", "enum": ["ru", "en"], "description": "Preferred language for type/member names"}
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
    public String getName() {
        return "bsl_scope_members"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Collect available BSL scope members (methods/properties/proposals) at source position."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
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
