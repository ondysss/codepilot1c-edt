package com.codepilot1c.core.tools.git;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.git.GitOperation;
import com.codepilot1c.core.git.GitService;
import com.codepilot1c.core.git.GitToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.workspace.WorkspaceImportException;
import com.codepilot1c.core.workspace.WorkspaceProjectImportResult;
import com.codepilot1c.core.workspace.WorkspaceProjectImportService;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Clones a Git repository and imports an Eclipse/EDT project from it into the workspace.
 */
@ToolMeta(name = "git_clone_and_import_project", category = "git", mutating = true, tags = {"workspace"})
public class GitCloneAndImportProjectTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "remote_url": {
                  "type": "string",
                  "description": "URL или локальный путь git-репозитория, который нужно сначала клонировать"
                },
                "repo_path": {
                  "type": "string",
                  "description": "Целевая директория для локального clone"
                },
                "branch": {
                  "type": "string",
                  "description": "Ветка для clone"
                },
                "project_subpath": {
                  "type": "string",
                  "description": "Подкаталог внутри клонированного репозитория, где лежит .project (default: .)"
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
              "required": ["remote_url", "repo_path"]
            }
            """; //$NON-NLS-1$

    private final GitService gitService;
    private final WorkspaceProjectImportService importService;

    public GitCloneAndImportProjectTool() {
        this(new GitService(), new WorkspaceProjectImportService());
    }

    public GitCloneAndImportProjectTool(GitService gitService, WorkspaceProjectImportService importService) {
        this.gitService = gitService;
        this.importService = importService;
    }

    @Override
    public String getDescription() {
        return "Клонирует git-репозиторий и сразу импортирует лежащий в нем Eclipse or EDT project в workspace. Используй, когда исходная точка еще не находится локально. Если репозиторий уже клонирован, предпочитай workspace_import_project."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("git-clone-import"); //$NON-NLS-1$
            try {
                String remoteUrl = asString(parameters.get("remote_url")); //$NON-NLS-1$
                Path repoPath = Path.of(asString(parameters.get("repo_path"))); //$NON-NLS-1$
                String branch = asOptionalString(parameters.get("branch")); //$NON-NLS-1$
                String projectSubpath = asOptionalString(parameters.get("project_subpath")); //$NON-NLS-1$
                boolean open = parameters == null || !Boolean.FALSE.equals(parameters.get("open")); //$NON-NLS-1$
                boolean refresh = parameters == null || !Boolean.FALSE.equals(parameters.get("refresh")); //$NON-NLS-1$

                JsonObject cloneResult = gitService.mutate(opId, GitOperation.CLONE, repoPath,
                        Map.of(
                                "remote_url", remoteUrl, //$NON-NLS-1$
                                "branch", branch == null ? "" : branch)); //$NON-NLS-1$

                Path projectPath = projectSubpath == null || ".".equals(projectSubpath) //$NON-NLS-1$
                        ? repoPath
                        : repoPath.resolve(projectSubpath).normalize();
                WorkspaceProjectImportResult importResult = importService.importProject(projectPath, open, refresh);

                JsonObject json = new JsonObject();
                json.addProperty("op_id", opId); //$NON-NLS-1$
                json.addProperty("status", "completed"); //$NON-NLS-1$ //$NON-NLS-2$
                json.addProperty("remote_url", remoteUrl); //$NON-NLS-1$
                json.addProperty("repo_path", repoPath.toAbsolutePath().normalize().toString()); //$NON-NLS-1$
                json.addProperty("project_path", projectPath.toAbsolutePath().normalize().toString()); //$NON-NLS-1$
                json.add("clone", cloneResult); //$NON-NLS-1$

                JsonObject imported = new JsonObject();
                imported.addProperty("project_name", importResult.projectName()); //$NON-NLS-1$
                imported.addProperty("created", importResult.created()); //$NON-NLS-1$
                imported.addProperty("opened", importResult.opened()); //$NON-NLS-1$
                imported.addProperty("refreshed", importResult.refreshed()); //$NON-NLS-1$
                json.add("imported", imported); //$NON-NLS-1$
                return ToolResult.success(pretty(json), ToolResult.ToolResultType.CONFIRMATION);
            } catch (InvalidPathException | NullPointerException e) {
                return ToolResult.failure(pretty(error(opId, "INVALID_ARGUMENT", e.getMessage()))); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (GitToolException e) {
                return ToolResult.failure(pretty(error(opId, e.getCode().name(), e.getMessage())));
            } catch (WorkspaceImportException e) {
                return ToolResult.failure(pretty(error(opId, e.getCode().name(), e.getMessage())));
            } catch (Exception e) {
                return ToolResult.failure(pretty(error(opId, "CLONE_IMPORT_FAILED", e.getMessage()))); //$NON-NLS-1$
            }
        });
    }

    private static JsonObject error(String opId, String code, String message) {
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

    private static String asOptionalString(Object value) {
        String raw = asString(value);
        return raw == null || raw.isBlank() ? null : raw;
    }
}
