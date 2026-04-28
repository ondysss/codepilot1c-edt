package com.codepilot1c.core.tools.debug;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtDebugServices;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.SetBreakpointRequest;
import com.codepilot1c.core.edt.ast.SetBreakpointResult;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

@ToolMeta(
    name = "set_breakpoint",
    category = "debug",
    surfaceCategory = "edt_semantic_write",
    mutating = true,
    tags = {"debug", "breakpoint"})
public class SetBreakpointTool extends AbstractTool {
    
    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SetBreakpointTool.class);
    private static final Gson GSON = new Gson();
    
    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "projectName": {"type": "string", "description": "EDT project name"},
            "filePath": {"type": "string", "description": "Path to BSL module relative to src/"},
            "line": {"type": "integer", "description": "Line number of breakpoint"},
            "condition": {"type": "string", "description": "Optional breakpoint condition"},
            "enabled": {"type": "boolean", "description": "Enable breakpoint (default: true)"}
          },
          "required": ["projectName", "filePath", "line"]
        }
        """;
    
    @Override
    public String getDescription() {
        return "Set a breakpoint at a specific location in the EDT project.";
    }
    
    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }
    
    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("set-breakpoint");
            Map<String, Object> parameters = params.getRaw();
            try {
                SetBreakpointRequest request = SetBreakpointRequest.fromParameters(parameters);
                LOG.info("[%s] START set_breakpoint project=%s line=%d", opId, request.projectName(), request.line());
                SetBreakpointResult result = EdtDebugServices.getInstance().setBreakpoint(request);
                JsonObject structured = GSON.toJsonTree(result).getAsJsonObject();
                LOG.info("[%s] DONE set_breakpoint created=%s", opId, result.created());
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION, structured);
            } catch (EdtAstException e) {
                LOG.warn("[%s] set_breakpoint failed: %s", opId, e.getMessage());
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                LOG.error("[" + opId + "] set_breakpoint failed", e);
                return ToolResult.failure(errorJson("INTERNAL_ERROR", e.getMessage(), false));
            }
        });
    }
    
    private static String toErrorJson(EdtAstException e) {
        return errorJson(e.getCode().name(), e.getMessage(), e.isRecoverable());
    }
    
    private static String errorJson(String code, String message, boolean recoverable) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", code == null ? "" : code);
        obj.addProperty("message", message == null ? "" : message);
        obj.addProperty("recoverable", recoverable);
        return GSON.toJson(obj);
    }
}
