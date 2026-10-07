package com.codepilot1c.core.tools.workspace;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.workspace.EdtProjectContextService;
import com.codepilot1c.core.workspace.EdtProjectContextService.ContextException;
import com.google.gson.Gson;

@ToolMeta(name = "edt_project_context", category = "workspace", mutating = true, tags = {"workspace", "edt"})
public class EdtProjectContextTool extends AbstractTool {
    private final EdtProjectContextService service;
    private static final Gson GSON = new Gson();
    public EdtProjectContextTool() { this(new EdtProjectContextService()); }
    public EdtProjectContextTool(EdtProjectContextService service) { this.service = service; }
    @Override public String getDescription() {
        return "Читает или заменяет контекст проекта через native API EDT Extension Tweaks. Показывает сохраненные и действующие связи; set сначала запускать с dry_run=true, затем dry_run=false. clean_build пересобирает только выбранный проект.";
    }
    @Override public String getParameterSchema() {
        return """
            {"type":"object","properties":{
              "project_name":{"type":"string","description":"Exact open EDT project name"},
              "action":{"type":"string","enum":["inspect","set"],"description":"Default inspect"},
              "context_projects":{"type":"array","items":{"type":"string"},"description":"Full replacement for set; [] explicitly clears links"},
              "dry_run":{"type":"boolean","description":"Default true; false required to save set"},
              "clean_build":{"type":"boolean","description":"Default false; clean/full build of target only after verified save"}
            },"required":["project_name"],"additionalProperties":false}
            """;
    }
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String,Object> raw = params.getRaw();
                String project = params.requireString("project_name");
                Object actionValue = raw.getOrDefault("action", "inspect");
                if (!(actionValue instanceof String action)) throw new ContextException("INVALID_ARGUMENT", "action must be a string");
                Object contextsValue = raw.get("context_projects");
                if (contextsValue != null && !(contextsValue instanceof List<?>)) throw new ContextException("INVALID_ARGUMENT", "context_projects must be an array");
                boolean dryRun = booleanParam(raw, "dry_run", true);
                boolean build = booleanParam(raw, "clean_build", false);
                var result = service.execute(project, action, (List<?>)contextsValue, dryRun, build);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION,
                        GSON.toJsonTree(result).getAsJsonObject());
            } catch (ContextException e) {
                return ToolResult.failure("[" + e.code + "] " + e.getMessage());
            } catch (Exception e) {
                return ToolResult.failure("CONTEXT_OPERATION_FAILED: " + e.getMessage());
            }
        });
    }
    private static boolean booleanParam(Map<String,Object> raw, String name, boolean fallback) {
        Object value = raw.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw new ContextException("INVALID_ARGUMENT", name + " must be boolean");
        return flag;
    }
}
