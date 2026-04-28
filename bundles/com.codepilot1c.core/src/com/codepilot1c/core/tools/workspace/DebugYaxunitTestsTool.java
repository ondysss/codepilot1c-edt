/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

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

import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
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
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Launches YAXUnit tests in debug-oriented TestManager mode.
 */
@ToolMeta(
        name = "debug_yaxunit_tests",
        category = "diagnostics",
        surfaceCategory = "debugging",
        mutating = true,
        tags = {"workspace", "debugging", "yaxunit"})
public class DebugYaxunitTestsTool extends AbstractTool {

    public interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(DebugYaxunitTestsTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int LOG_TAIL_LINES = 50;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project name"
                },
                "filters": {
                  "type": "string",
                  "description": "YAXUnit test filters (comma-separated)"
                },
                "launch_config_name": {
                  "type": "string",
                  "description": "Launch configuration name (debug mode)"
                },
                "wait_for_debugger": {
                  "type": "boolean",
                  "description": "Wait until debugger attaches before starting tests (default: true)"
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Timeout in seconds (default: 600)"
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

    public DebugYaxunitTestsTool() {
        this(new EdtProjectResolver(), new EdtLaunchContextBuilder(), new EdtRuntimeService(),
                ProcessBuilder::start, EdtLaunchProcessRegistry.getInstance());
    }

    DebugYaxunitTestsTool(EdtProjectResolver projectResolver, EdtLaunchContextBuilder contextBuilder,
            EdtRuntimeService runtimeService, ProcessStarter processStarter,
            EdtLaunchProcessRegistry processRegistry) {
        this.projectResolver = projectResolver;
        this.contextBuilder = contextBuilder;
        this.runtimeService = runtimeService;
        this.processStarter = processStarter;
        this.processRegistry = processRegistry;
    }

    @Override
    public String getDescription() {
        return "Starts YAXUnit tests in debug mode and returns process/debug attach details."; //$NON-NLS-1$
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
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("yaxunit-debug"); //$NON-NLS-1$
            LOG.info("[%s] START debug_yaxunit_tests", opId); //$NON-NLS-1$

            File workspaceRoot = getWorkspaceRoot();
            String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
            String filters = asOptionalString(parameters == null ? null : parameters.get("filters")); //$NON-NLS-1$
            String launchConfigName = asOptionalString(
                    parameters == null ? null : parameters.get("launch_config_name")); //$NON-NLS-1$
            boolean waitForDebugger = parameters == null || !Boolean.FALSE.equals(parameters.get("wait_for_debugger")); //$NON-NLS-1$
            int timeoutSeconds = extractTimeoutSeconds(parameters);

            File runDir = buildRunDirectory(workspaceRoot, opId);
            File logFile = new File(runDir, "yaxunit-debug.log"); //$NON-NLS-1$
            long startTime = System.currentTimeMillis();
            try {
                InfobaseReference infobase = projectResolver.resolveInfobase(projectName, workspaceRoot);
                EdtResolvedLaunchInputs inputs = projectResolver.resolveLaunchInputs(projectName, workspaceRoot);
                EdtResolvedLaunchContext context = contextBuilder.build(inputs);
                File paramsFile = writeParamsFile(runDir, filters, waitForDebugger);

                RuntimeExecutionCommandBuilder commandBuilder = runtimeService.buildTestManagerCommand(
                        projectName,
                        null,
                        paramsFile,
                        workspaceRoot,
                        true,
                        true,
                        false,
                        logFile,
                        context.runtimeVersion(),
                        context.accessSettings());
                commandBuilder.startupOption(buildStartupOption(filters, waitForDebugger));

                ProcessBuilder processBuilder = commandBuilder.toProcessBuilder();
                processBuilder.directory(workspaceRoot);
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

                Process process = processStarter.start(processBuilder);
                Thread.sleep(1000L);

                JsonObject result = basePayload(opId, "debug_started", projectName, workspaceRoot, runDir, //$NON-NLS-1$
                        paramsFile, logFile, processBuilder.command(), infobase, context, launchConfigName,
                        waitForDebugger, timeoutSeconds);
                result.addProperty("pid", process.pid()); //$NON-NLS-1$
                result.addProperty("process_started", process.isAlive()); //$NON-NLS-1$
                result.addProperty("duration_ms", System.currentTimeMillis() - startTime); //$NON-NLS-1$
                if (!matchesLaunchConfigName(launchConfigName, context.launchConfigurationFile())) {
                    result.addProperty("launch_config_note", //$NON-NLS-1$
                            "Requested launch_config_name was recorded, but EDT runtime resolution used the project RuntimeClient configuration"); //$NON-NLS-1$
                }

                if (!process.isAlive()) {
                    result.addProperty("status", "failed"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.addProperty("exit_code", process.exitValue()); //$NON-NLS-1$
                    result.addProperty("error_code", EdtToolErrorCode.PROCESS_START_FAILED.name()); //$NON-NLS-1$
                    result.addProperty("message", "YAXUnit debug process exited immediately"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.addProperty("tail_log", tailLog(logFile, LOG_TAIL_LINES)); //$NON-NLS-1$
                    return ToolResult.failure(pretty(result));
                }

                processRegistry.register(opId, process);
                result.addProperty("tail_log", tailLog(logFile, LOG_TAIL_LINES)); //$NON-NLS-1$
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE, result);
            } catch (EdtToolException e) {
                return failure(opId, projectName, workspaceRoot, runDir, logFile, e.getCode(), e.getMessage(),
                        startTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(opId, projectName, workspaceRoot, runDir, logFile, EdtToolErrorCode.PROCESS_TIMEOUT,
                        "YAXUnit debug startup interrupted", startTime); //$NON-NLS-1$
            } catch (Exception e) {
                return failure(opId, projectName, workspaceRoot, runDir, logFile,
                        EdtToolErrorCode.PROCESS_START_FAILED, e.getMessage(), startTime);
            }
        });
    }

    protected File buildRunDirectory(File workspaceRoot, String opId) {
        File root = workspaceRoot == null
                ? new File(System.getProperty("java.io.tmpdir"), "codepilot1c-yaxunit-debug/" + opId) //$NON-NLS-1$ //$NON-NLS-2$
                : new File(workspaceRoot, ".codepilot/runs/yaxunit-debug/" + opId); //$NON-NLS-1$
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    protected File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null : ResourcesPlugin.getWorkspace().getRoot();
        return root == null || root.getLocation() == null ? null : root.getLocation().toFile();
    }

    private ToolResult failure(String opId, String projectName, File workspaceRoot, File runDir, File logFile,
            EdtToolErrorCode code, String message, long startTime) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("workspace_root", workspaceRoot == null ? "" : workspaceRoot.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("run_dir", runDir == null ? "" : runDir.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("log_path", logFile == null ? "" : logFile.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("error_code", code.name()); //$NON-NLS-1$
        result.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("duration_ms", System.currentTimeMillis() - startTime); //$NON-NLS-1$
        result.addProperty("tail_log", tailLog(logFile, LOG_TAIL_LINES)); //$NON-NLS-1$
        return ToolResult.failure(pretty(result));
    }

    private static File writeParamsFile(File runDir, String filters, boolean waitForDebugger) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("command", "RunUnitTests"); //$NON-NLS-1$ //$NON-NLS-2$
        params.addProperty("debug", true); //$NON-NLS-1$
        params.addProperty("wait_for_debugger", waitForDebugger); //$NON-NLS-1$
        if (filters != null && !filters.isBlank()) {
            params.addProperty("filter", filters); //$NON-NLS-1$
        }
        File paramsFile = new File(runDir, "yaxunit-debug-params.json"); //$NON-NLS-1$
        Files.writeString(paramsFile.toPath(), pretty(params), StandardCharsets.UTF_8);
        return paramsFile;
    }

    private static String buildStartupOption(String filters, boolean waitForDebugger) {
        StringBuilder option = new StringBuilder("RunUnitTests;Debug"); //$NON-NLS-1$
        if (waitForDebugger) {
            option.append(";WaitForDebugger"); //$NON-NLS-1$
        }
        if (filters != null && !filters.isBlank()) {
            option.append(";Filter=").append(filters.trim()); //$NON-NLS-1$
        }
        return option.toString();
    }

    private static JsonObject basePayload(String opId, String status, String projectName, File workspaceRoot,
            File runDir, File paramsFile, File logFile, List<String> command, InfobaseReference infobase,
            EdtResolvedLaunchContext context, String launchConfigName, boolean waitForDebugger,
            int timeoutSeconds) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("status", status); //$NON-NLS-1$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("workspace_root", workspaceRoot == null ? "" : workspaceRoot.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("run_dir", runDir == null ? "" : runDir.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("params_path", paramsFile == null ? "" : paramsFile.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("log_path", logFile == null ? "" : logFile.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("wait_for_debugger", waitForDebugger); //$NON-NLS-1$
        result.addProperty("timeout_s", timeoutSeconds); //$NON-NLS-1$
        if (launchConfigName != null) {
            result.addProperty("launch_config_name", launchConfigName); //$NON-NLS-1$
        }
        if (context != null && context.launchConfigurationFile() != null) {
            result.addProperty("resolved_launch_config_path", //$NON-NLS-1$
                    context.launchConfigurationFile().getAbsolutePath());
        }
        if (context != null && context.clientFile() != null) {
            result.addProperty("client_executable", context.clientFile().getAbsolutePath()); //$NON-NLS-1$
        }
        if (context != null && context.runtimeVersion() != null) {
            result.addProperty("runtime_version", context.runtimeVersion()); //$NON-NLS-1$
        }
        if (infobase != null && infobase.getConnectionString() != null) {
            result.addProperty("infobase_connection", infobase.getConnectionString().asConnectionString()); //$NON-NLS-1$
        }
        result.add("command", toCommandJson(command)); //$NON-NLS-1$
        return result;
    }

    private static JsonObject toCommandJson(List<String> command) {
        JsonObject obj = new JsonObject();
        if (command == null || command.isEmpty()) {
            return obj;
        }
        obj.addProperty("bin", command.get(0)); //$NON-NLS-1$
        JsonArray args = new JsonArray();
        for (int i = 1; i < command.size(); i++) {
            args.add(isSensitiveArgument(command, i) ? "<redacted>" : command.get(i)); //$NON-NLS-1$
        }
        obj.add("args", args); //$NON-NLS-1$
        return obj;
    }

    private static boolean isSensitiveArgument(List<String> command, int index) {
        if (index <= 0 || index >= command.size()) {
            return false;
        }
        String previous = command.get(index - 1);
        return "/IBConnectionString".equalsIgnoreCase(previous) //$NON-NLS-1$
                || "/P".equalsIgnoreCase(previous); //$NON-NLS-1$
    }

    private static boolean matchesLaunchConfigName(String requestedName, File resolvedFile) {
        if (requestedName == null || requestedName.isBlank() || resolvedFile == null) {
            return true;
        }
        String fileName = resolvedFile.getName();
        if (fileName.endsWith(".launch")) { //$NON-NLS-1$
            fileName = fileName.substring(0, fileName.length() - ".launch".length()); //$NON-NLS-1$
        }
        return requestedName.equals(fileName);
    }

    private static int extractTimeoutSeconds(Map<String, Object> parameters) {
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
                "timeout_s must be a positive integer"); //$NON-NLS-1$
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
