package com.codepilot1c.core.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.edt.runtime.EdtLaunchContextBuilder;
import com.codepilot1c.core.edt.runtime.EdtLaunchProcessRegistry;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtResolvedLaunchContext;
import com.codepilot1c.core.edt.runtime.EdtResolvedLaunchInputs;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Launches an EDT project's application using EDT runtime resolution.
 */
public class EdtLaunchAppTool implements ITool {

    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtLaunchAppTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int LOG_TAIL_LINES = 50;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project name"
                },
                "wait_for_exit": {
                  "type": "boolean",
                  "description": "Wait for the launched process to exit"
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Timeout in seconds when wait_for_exit=true"
                },
                "additional_parameters": {
                  "type": "string",
                  "description": "Additional 1C client command line parameters"
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Resolve command without starting the process"
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectResolver projectResolver;
    private final EdtLaunchContextBuilder contextBuilder;
    private final EdtRuntimeService runtimeService;
    private final ProcessStarter processStarter;
    private final EdtLaunchProcessRegistry processRegistry;

    public EdtLaunchAppTool() {
        this(new EdtProjectResolver(), new EdtLaunchContextBuilder(), new EdtRuntimeService(),
                ProcessBuilder::start, EdtLaunchProcessRegistry.getInstance());
    }

    EdtLaunchAppTool(EdtProjectResolver projectResolver, EdtLaunchContextBuilder contextBuilder,
            EdtRuntimeService runtimeService, ProcessStarter processStarter,
            EdtLaunchProcessRegistry processRegistry) {
        this.projectResolver = projectResolver;
        this.contextBuilder = contextBuilder;
        this.runtimeService = runtimeService;
        this.processStarter = processStarter;
        this.processRegistry = processRegistry;
    }

    @Override
    public String getName() {
        return "edt_launch_app"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Запускает приложение EDT проекта через EDT runtime и RuntimeClient launch configuration."; //$NON-NLS-1$
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
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-launch"); //$NON-NLS-1$
            LOG.info("[%s] START edt_launch_app", opId); //$NON-NLS-1$
            File workspaceRoot = getWorkspaceRoot();
            String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
            boolean waitForExit = parameters != null && Boolean.TRUE.equals(parameters.get("wait_for_exit")); //$NON-NLS-1$
            boolean dryRun = parameters != null && Boolean.TRUE.equals(parameters.get("dry_run")); //$NON-NLS-1$
            String additionalParameters = asOptionalString(
                    parameters == null ? null : parameters.get("additional_parameters")); //$NON-NLS-1$
            int timeoutSeconds = extractTimeoutSeconds(parameters, waitForExit);
            File runDir = buildRunDirectory(workspaceRoot, opId);
            File logFile = new File(runDir, "launch.log"); //$NON-NLS-1$
            try {
                EdtResolvedLaunchInputs inputs = projectResolver.resolveLaunchInputs(projectName, workspaceRoot);
                EdtResolvedLaunchContext context = contextBuilder.build(inputs);
                ProcessBuilder processBuilder = runtimeService.buildEnterpriseLaunchProcess(context,
                        additionalParameters, logFile);
                processBuilder.directory(workspaceRoot);
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

                JsonObject result = basePayload(opId, dryRun ? "dry_run" : "ready", projectName, dryRun, //$NON-NLS-1$ //$NON-NLS-2$
                        workspaceRoot, context, processBuilder.command(), logFile);
                if (dryRun) {
                    return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
                }

                Process process = processStarter.start(processBuilder);
                result.addProperty("pid", process.pid()); //$NON-NLS-1$
                if (!waitForExit) {
                    processRegistry.register(opId, process);
                    result.addProperty("status", "started"); //$NON-NLS-1$ //$NON-NLS-2$
                    return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
                }

                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    result.addProperty("status", "timeout"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.addProperty("timed_out", true); //$NON-NLS-1$
                    result.addProperty("error_code", EdtToolErrorCode.PROCESS_TIMEOUT.name()); //$NON-NLS-1$
                    result.addProperty("message",
                            "Process timed out after " + timeoutSeconds + " seconds"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.addProperty("tail_log", tailLog(logFile, LOG_TAIL_LINES)); //$NON-NLS-1$
                    return ToolResult.failure(pretty(result));
                }

                result.addProperty("status", process.exitValue() == 0 ? "completed" : "failed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                result.addProperty("exit_code", process.exitValue()); //$NON-NLS-1$
                result.addProperty("timed_out", false); //$NON-NLS-1$
                result.addProperty("tail_log", tailLog(logFile, LOG_TAIL_LINES)); //$NON-NLS-1$
                if (process.exitValue() == 0) {
                    return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
                }
                result.addProperty("error_code", EdtToolErrorCode.PROCESS_START_FAILED.name()); //$NON-NLS-1$
                result.addProperty("message", "Launched process exited with a non-zero code"); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure(pretty(result));
            } catch (EdtToolException e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName, workspaceRoot, logFile,
                        e.getCode(), e.getMessage())));
            } catch (IOException e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName, workspaceRoot, logFile,
                        EdtToolErrorCode.PROCESS_START_FAILED, e.getMessage())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure(pretty(errorPayload(opId, projectName, workspaceRoot, logFile,
                        EdtToolErrorCode.PROCESS_TIMEOUT, "Launch wait interrupted"))); //$NON-NLS-1$
            } catch (Exception e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName, workspaceRoot, logFile,
                        EdtToolErrorCode.PROCESS_START_FAILED, e.getMessage())));
            }
        });
    }

    private static JsonObject basePayload(String opId, String status, String projectName, boolean dryRun,
            File workspaceRoot, EdtResolvedLaunchContext context, List<String> command, File logFile) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("status", status); //$NON-NLS-1$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("dry_run", dryRun); //$NON-NLS-1$
        result.addProperty("workspace_root", workspaceRoot == null ? "" : workspaceRoot.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("runtime_version",
                context.runtimeVersion() == null ? "" : context.runtimeVersion()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("launch_config_path",
                context.launchConfigurationFile() == null ? "" : context.launchConfigurationFile().getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("log_path", logFile == null ? "" : logFile.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        if (context.infobase() != null && context.infobase().getConnectionString() != null) {
            result.addProperty("infobase_connection",
                    context.infobase().getConnectionString().asConnectionString()); //$NON-NLS-1$
        }
        JsonArray commandJson = new JsonArray();
        for (String item : command) {
            commandJson.add(item);
        }
        result.add("command", commandJson); //$NON-NLS-1$
        return result;
    }

    private static JsonObject errorPayload(String opId, String projectName, File workspaceRoot, File logFile,
            EdtToolErrorCode code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("dry_run", false); //$NON-NLS-1$
        result.addProperty("workspace_root", workspaceRoot == null ? "" : workspaceRoot.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("log_path", logFile == null ? "" : logFile.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("error_code", code.name()); //$NON-NLS-1$
        result.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    protected File buildRunDirectory(File workspaceRoot, String opId) {
        File root = workspaceRoot == null
                ? new File(System.getProperty("java.io.tmpdir"), "codepilot1c-edt-launch") //$NON-NLS-1$ //$NON-NLS-2$
                : new File(workspaceRoot, ".codepilot/runs/edt_launch_app/" + opId); //$NON-NLS-1$
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    protected File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null : ResourcesPlugin.getWorkspace().getRoot();
        return root == null || root.getLocation() == null ? null : root.getLocation().toFile();
    }

    private static int extractTimeoutSeconds(Map<String, Object> parameters, boolean waitForExit) {
        if (!waitForExit) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        Object value = parameters == null ? null : parameters.get("timeout_s"); //$NON-NLS-1$
        if (value == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        if (value instanceof Number number) {
            int timeout = number.intValue();
            if (timeout > 0) {
                return timeout;
            }
        }
        throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                "timeout_s must be a positive integer when wait_for_exit=true"); //$NON-NLS-1$
    }

    private static String tailLog(File logFile, int maxLines) {
        if (logFile == null || !logFile.isFile()) {
            return ""; //$NON-NLS-1$
        }
        try {
            Deque<String> lines = new ArrayDeque<>();
            for (String line : Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8)) {
                lines.addLast(line);
                while (lines.size() > maxLines) {
                    lines.removeFirst();
                }
            }
            return String.join("\n", lines); //$NON-NLS-1$
        } catch (IOException e) {
            return ""; //$NON-NLS-1$
        }
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String asOptionalString(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }
}
