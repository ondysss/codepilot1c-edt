package com.codepilot1c.core.tools.workspace;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.workspace.WorkspaceImportException;
import com.codepilot1c.core.workspace.WorkspaceProjectImportResult;
import com.codepilot1c.core.workspace.WorkspaceProjectImportService;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Imports an existing Eclipse/EDT project into the current workspace.
 */
@ToolMeta(name = "workspace_import_project", category = "workspace", mutating = true, tags = {"workspace"})
public class WorkspaceImportProjectTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(WorkspaceImportProjectTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Путь к уже существующей директории Eclipse or EDT project с файлом .project"
                },
                "open": {
                  "type": "boolean",
                  "description": "Открыть проект после импорта (default: true)"
                },
                "refresh": {
                  "type": "boolean",
                  "description": "Обновить проект после импорта (default: true)"
                }
              },
              "required": ["path"]
            }
            """; //$NON-NLS-1$

    private final WorkspaceProjectImportService importService;

    public WorkspaceImportProjectTool() {
        this(new WorkspaceProjectImportService());
    }

    public WorkspaceImportProjectTool(WorkspaceProjectImportService importService) {
        this.importService = importService;
    }

    @Override
    public String getDescription() {
        return "Импортирует уже существующий локальный Eclipse or EDT project в текущий workspace. Используй, когда проект уже лежит на диске. Не клонирует git и не создает проект из инфобазы; для этого есть git_clone_and_import_project и import_project_from_infobase."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("workspace-import"); //$NON-NLS-1$
            String rawPath = asString(parameters == null ? null : parameters.get("path")); //$NON-NLS-1$
            boolean open = parameters == null || !Boolean.FALSE.equals(parameters.get("open")); //$NON-NLS-1$
            boolean refresh = parameters == null || !Boolean.FALSE.equals(parameters.get("refresh")); //$NON-NLS-1$
            LOG.info("[%s] START workspace_import_project path=%s open=%s refresh=%s", opId, rawPath,
                    Boolean.valueOf(open), Boolean.valueOf(refresh)); //$NON-NLS-1$
            try {
                Path path = Path.of(rawPath);
                WorkspaceProjectImportResult result = importService.importProject(path, open, refresh);
                return ToolResult.success(pretty(successPayload(opId, result)), ToolResult.ToolResultType.CONFIRMATION);
            } catch (InvalidPathException | NullPointerException e) {
                return ToolResult.failure(pretty(errorPayload(opId, "INVALID_ARGUMENT", e.getMessage()))); //$NON-NLS-1$
            } catch (WorkspaceImportException e) {
                return ToolResult.failure(pretty(errorPayload(opId, e.getCode().name(), e.getMessage())));
            } catch (Exception e) {
                return ToolResult.failure(pretty(errorPayload(opId, "IMPORT_FAILED", e.getMessage()))); //$NON-NLS-1$
            }
        });
    }

    private static JsonObject successPayload(String opId, WorkspaceProjectImportResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("status", "imported"); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("project_name", result.projectName()); //$NON-NLS-1$
        json.addProperty("project_path", result.projectPath()); //$NON-NLS-1$
        json.addProperty("created", result.created()); //$NON-NLS-1$
        json.addProperty("opened", result.opened()); //$NON-NLS-1$
        json.addProperty("refreshed", result.refreshed()); //$NON-NLS-1$
        return json;
    }

    private static JsonObject errorPayload(String opId, String code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("error_code", code == null ? "" : code); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        return json;
    }

    private static String pretty(JsonObject json) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(json);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
