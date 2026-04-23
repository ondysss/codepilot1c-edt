package com.codepilot1c.core.tools.bsl;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.lang.BslMethodAnalysisRequest;
import com.codepilot1c.core.edt.lang.BslMethodAnalysisResult;
import com.codepilot1c.core.edt.lang.BslMethodLookupException;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Performs structural analysis for one BSL method.
 */
@ToolMeta(name = "bsl_analyze_method", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class BslAnalyzeMethodTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project containing the BSL module"},
                "filePath": {"type": "string", "description": "Path to the module relative to src/, for example CommonModules/MyModule/Module.bsl"},
                "methodName": {"type": "string", "description": "Exact procedure/function name to analyze semantically"},
                "kind": {"type": "string", "enum": ["any", "procedure", "function"], "description": "Optional method kind filter when the name is ambiguous"},
                "start_line": {"type": "integer", "description": "Optional exact start line when several methods share the same name"}
              },
              "required": ["projectName", "filePath", "methodName"]
            }
            """; //$NON-NLS-1$

    private final BslSemanticService service;

    public BslAnalyzeMethodTool() {
        this(new BslSemanticService());
    }

    public BslAnalyzeMethodTool(BslSemanticService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Анализирует один BSL-метод: сложность, вызовы, неиспользуемые параметры и рискованные ветки."; //$NON-NLS-1$
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
                BslMethodAnalysisRequest request = BslMethodAnalysisRequest.fromParameters(parameters);
                BslMethodAnalysisResult result = service.analyzeMethod(request);
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
