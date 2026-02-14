package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.lang.BslPositionRequest;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.codepilot1c.core.edt.lang.BslTypeResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Returns inferred BSL types at source position.
 */
public class BslTypeAtPositionTool implements ITool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "filePath": {"type": "string", "description": "Path relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "line": {"type": "integer", "description": "1-based line"},
                "column": {"type": "integer", "description": "1-based column"}
              },
              "required": ["projectName", "filePath", "line", "column"]
            }
            """; //$NON-NLS-1$

    private final BslSemanticService service;

    public BslTypeAtPositionTool() {
        this(new BslSemanticService());
    }

    BslTypeAtPositionTool(BslSemanticService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "bsl_type_at_position"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Infer BSL expression types at source position via EDT language model."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BslPositionRequest request = BslPositionRequest.fromParameters(parameters);
                BslTypeResult result = service.getTypeAtPosition(request);
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
