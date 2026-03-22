package com.codepilot1c.core.tools.git;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.git.GitErrorCode;
import com.codepilot1c.core.git.GitOperation;
import com.codepilot1c.core.git.GitService;
import com.codepilot1c.core.git.GitToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Mutating structured Git operations.
 */
@ToolMeta(name = "git_mutate", category = "git", mutating = true, tags = {"workspace"})
public class GitMutateTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "enum": ["init", "create", "create_repo", "clone", "remote_add", "remote_set_url", "fetch", "pull", "push", "checkout", "create_branch", "add", "commit"]
                },
                "repo_path": {
                  "type": "string",
                  "description": "Путь к локальному git-репозиторию или целевой директории"
                },
                "remote_name": {
                  "type": "string",
                  "description": "Имя remote (default: origin)"
                },
                "remote_url": {
                  "type": "string",
                  "description": "URL удаленного репозитория"
                },
                "branch": {
                  "type": "string",
                  "description": "Имя ветки"
                },
                "start_point": {
                  "type": "string",
                  "description": "Стартовый ref для create_branch"
                },
                "checkout": {
                  "type": "boolean",
                  "description": "Сразу перейти на новую ветку для create_branch"
                },
                "initial_branch": {
                  "type": "string",
                  "description": "Имя стартовой ветки для init"
                },
                "set_upstream": {
                  "type": "boolean",
                  "description": "Использовать --set-upstream для push"
                },
                "paths": {
                  "type": ["array", "string"],
                  "description": "Пути для add"
                },
                "message": {
                  "type": "string",
                  "description": "Сообщение коммита для commit"
                }
              },
              "required": ["operation", "repo_path"]
            }
            """; //$NON-NLS-1$

    private final GitService gitService;

    public GitMutateTool() {
        this(new GitService());
    }

    GitMutateTool(GitService gitService) {
        this.gitService = gitService;
    }

    @Override
    public String getDescription() {
        return "Выполняет allowlisted мутации git-репозитория: create_repo/init, clone, remote, fetch/pull/push, branch, add, commit."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        Map<String, Object> parameters = params.getRaw();
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("git-mutate"); //$NON-NLS-1$
            try {
                GitOperation operation = GitOperation.from(asString(parameters.get("operation"))); //$NON-NLS-1$
                Path repoPath = Path.of(asString(parameters.get("repo_path"))); //$NON-NLS-1$
                JsonObject result = gitService.mutate(opId, operation, repoPath, parameters);
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (InvalidPathException | NullPointerException e) {
                return ToolResult.failure(pretty(errorPayload(opId, GitErrorCode.INVALID_ARGUMENT.name(), e.getMessage())));
            } catch (GitToolException e) {
                return ToolResult.failure(pretty(errorPayload(opId, e.getCode().name(), e.getMessage())));
            } catch (Exception e) {
                return ToolResult.failure(pretty(errorPayload(opId, GitErrorCode.COMMAND_FAILED.name(), e.getMessage())));
            }
        });
    }

    private static JsonObject errorPayload(String opId, String code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("error_code", code); //$NON-NLS-1$
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
