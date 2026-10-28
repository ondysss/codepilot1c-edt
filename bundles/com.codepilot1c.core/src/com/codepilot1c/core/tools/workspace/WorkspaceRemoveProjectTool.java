package com.codepilot1c.core.tools.workspace;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.workspace.WorkspaceProjectRemovalService;
import com.google.gson.GsonBuilder;

@ToolMeta(name = "workspace_remove_project", category = "workspace", mutating = true, tags = {"workspace", "edt"})
public class WorkspaceRemoveProjectTool extends AbstractTool {
    private final WorkspaceProjectRemovalService service;

    public WorkspaceRemoveProjectTool() {
        this(new WorkspaceProjectRemovalService());
    }

    public WorkspaceRemoveProjectTool(WorkspaceProjectRemovalService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Снимает один проект с регистрации в рабочей области EDT через Eclipse API, сохраняя все исходники. "
                + "Сначала dry_run=true; для выполнения dry_run=false и expected_location из результата проверки. "
                + "Отказывает при зависимых проектах, расширениях базовой конфигурации и несохраненных редакторах. "
                + "Не удаляет расширение из информационной базы и не удаляет файлы с диска.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","additionalProperties":false,"properties":{
                  "project_name":{"type":"string","description":"Exact registered EDT/Eclipse project name"},
                  "dry_run":{"type":"boolean","default":true,"description":"Inspect without removing; default true"},
                  "expected_location":{"type":"string","description":"Exact source location returned by dry_run; required to apply"}
                },"required":["project_name"]}
                """;
    }

    @Override
    public boolean requiresConfirmation() { return true; }

    @Override
    public boolean isDestructive() { return true; }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> raw = params.getRaw();
            try {
                if (!Set.of("project_name", "dry_run", "expected_location").containsAll(raw.keySet())) {
                    throw new IllegalArgumentException("Unsupported argument; deleting source files is not supported");
                }
                if (raw.containsKey("dry_run") && !(raw.get("dry_run") instanceof Boolean)) {
                    throw new IllegalArgumentException("dry_run must be a boolean");
                }
                Object name = raw.get("project_name");
                if (!(name instanceof String)) {
                    throw new IllegalArgumentException("project_name must be a string");
                }
                String expected = raw.get("expected_location") instanceof String value ? value : null;
                var result = service.remove((String) name, !Boolean.FALSE.equals(raw.get("dry_run")), expected);
                return ToolResult.success(new GsonBuilder().setPrettyPrinting().create().toJson(result),
                        ToolResult.ToolResultType.CONFIRMATION);
            } catch (Exception error) {
                return ToolResult.failure(new GsonBuilder().create().toJson(Map.of(
                        "error_code", "PROJECT_REMOVAL_REFUSED", "message", String.valueOf(error.getMessage()))));
            }
        });
    }
}
