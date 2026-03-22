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
 * Read-only structured Git operations.
 */
@ToolMeta(name = "git_inspect", category = "git", tags = {"read-only", "workspace"})
public class GitInspectTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "enum": ["status", "branch_list", "remote_list", "log", "diff_summary"]
                },
                "repo_path": {
                  "type": "string",
                  "description": "Путь к локальному git-репозиторию"
                },
                "limit": {
                  "type": "integer",
                  "description": "Лимит записей для operation=log"
                },
                "base_ref": {
                  "type": "string",
                  "description": "Базовый ref для operation=diff_summary"
                },
                "head_ref": {
                  "type": "string",
                  "description": "Целевой ref для operation=diff_summary"
                }
              },
              "required": ["operation", "repo_path"]
            }
            """; //$NON-NLS-1$

    private final GitService gitService;

    public GitInspectTool() {
        this(new GitService());
    }

    GitInspectTool(GitService gitService) {
        this.gitService = gitService;
    }

    @Override
    public String getDescription() {
        return "Читает состояние git-репозитория через allowlisted операции."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        Map<String, Object> parameters = params.getRaw();
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("git-inspect"); //$NON-NLS-1$
            try {
                GitOperation operation = GitOperation.from(asString(parameters.get("operation"))); //$NON-NLS-1$
                Path repoPath = Path.of(asString(parameters.get("repo_path"))); //$NON-NLS-1$
                JsonObject result = gitService.inspect(opId, operation, repoPath, parameters);
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.SEARCH_RESULTS);
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
