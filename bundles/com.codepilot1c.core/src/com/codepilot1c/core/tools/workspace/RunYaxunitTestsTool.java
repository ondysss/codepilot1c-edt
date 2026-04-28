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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaJUnitReport;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Runs YAXUnit tests for a 1C:EDT project through the EDT runtime.
 */
@ToolMeta(
        name = "run_yaxunit_tests",
        category = "diagnostics",
        surfaceCategory = "testing",
        mutating = true,
        tags = {"workspace", "testing", "yaxunit"})
public class RunYaxunitTestsTool extends AbstractTool {

    public interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(RunYaxunitTestsTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_FAILURE_DETAILS = 20;
    private static final int LOG_TAIL_LINES = 80;

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
                "junit_xml_path": {
                  "type": "string",
                  "description": "Output JUnit XML file path (relative to workspace .codepilot/runs/yaxunit/)"
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Timeout in seconds (default: 300)"
                },
                "update_database": {
                  "type": "boolean",
                  "description": "Update database before running tests (default: false)"
                },
                "keep_connected": {
                  "type": "boolean",
                  "description": "Keep infobase connected after update (default: true)"
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectResolver projectResolver;
    private final EdtRuntimeService runtimeService;
    private final ProcessStarter processStarter;

    public RunYaxunitTestsTool() {
        this(new EdtProjectResolver(), new EdtRuntimeService(), ProcessBuilder::start);
    }

    RunYaxunitTestsTool(EdtProjectResolver projectResolver, EdtRuntimeService runtimeService,
            ProcessStarter processStarter) {
        this.projectResolver = projectResolver;
        this.runtimeService = runtimeService;
        this.processStarter = processStarter;
    }

    @Override
    public String getDescription() {
        return "Runs YAXUnit tests, parses JUnit XML, and returns a Markdown report."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("yaxunit-run"); //$NON-NLS-1$
            LOG.info("[%s] START run_yaxunit_tests", opId); //$NON-NLS-1$

            File workspaceRoot = getWorkspaceRoot();
            String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
            String filters = asOptionalString(parameters == null ? null : parameters.get("filters")); //$NON-NLS-1$
            String junitXmlPath = asOptionalString(parameters == null ? null : parameters.get("junit_xml_path")); //$NON-NLS-1$
            boolean updateDatabase = parameters != null && Boolean.TRUE.equals(parameters.get("update_database")); //$NON-NLS-1$
            boolean keepConnected = parameters == null || !Boolean.FALSE.equals(parameters.get("keep_connected")); //$NON-NLS-1$
            int timeoutSeconds = extractTimeoutSeconds(parameters, DEFAULT_TIMEOUT_SECONDS);

            File runDir = buildRunDirectory(workspaceRoot, opId);
            File logFile = new File(runDir, "yaxunit.log"); //$NON-NLS-1$
            File junitXmlFile = null;
            long startTime = System.currentTimeMillis();
            try {
                InfobaseReference infobase = projectResolver.resolveInfobase(projectName, workspaceRoot);
                junitXmlFile = resolveJunitXmlFile(runDir, junitXmlPath);
                ensureParentDirectory(junitXmlFile);

                if (updateDatabase) {
                    LOG.info("[%s] Updating infobase before YAXUnit run", opId); //$NON-NLS-1$
                    boolean updated = runtimeService.updateInfobase(projectName, keepConnected,
                            new NullProgressMonitor());
                    if (!updated) {
                        throw new EdtToolException(EdtToolErrorCode.UPDATE_FAILED,
                                "EDT update returned false for project: " + projectName); //$NON-NLS-1$
                    }
                }

                File paramsFile = writeParamsFile(runDir, filters, junitXmlFile, false);
                RuntimeExecutionCommandBuilder commandBuilder = runtimeService.buildTestManagerCommand(
                        projectName,
                        null,
                        paramsFile,
                        workspaceRoot,
                        false,
                        true,
                        false,
                        logFile);
                commandBuilder.startupOption(buildStartupOption(junitXmlFile, filters, false));

                ProcessBuilder processBuilder = commandBuilder.toProcessBuilder();
                processBuilder.directory(workspaceRoot);
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

                JsonObject result = basePayload(opId, "running", projectName, workspaceRoot, runDir, //$NON-NLS-1$
                        paramsFile, junitXmlFile, logFile, processBuilder.command(), infobase);
                Process process = processStarter.start(processBuilder);
                result.addProperty("pid", process.pid()); //$NON-NLS-1$

                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                long durationMs = System.currentTimeMillis() - startTime;
                if (!finished) {
                    terminateProcessTree(process);
                    result.addProperty("status", "timeout"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.addProperty("duration_ms", durationMs); //$NON-NLS-1$
                    result.addProperty("timeout_s", timeoutSeconds); //$NON-NLS-1$
                    result.addProperty("error_code", EdtToolErrorCode.PROCESS_TIMEOUT.name()); //$NON-NLS-1$
                    result.addProperty("message", "YAXUnit run timed out"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.addProperty("tail_log", tailLog(logFile, LOG_TAIL_LINES)); //$NON-NLS-1$
                    addEmptyReport(result);
                    result.addProperty("markdown_report", buildMarkdownReport(result)); //$NON-NLS-1$
                    return ToolResult.failure(pretty(result));
                }

                int exitCode = process.exitValue();
                QaJUnitReport report = parseReport(junitXmlFile);
                addReport(result, report);
                result.addProperty("status", resolveStatus(exitCode, report)); //$NON-NLS-1$
                result.addProperty("exit_code", exitCode); //$NON-NLS-1$
                result.addProperty("duration_ms", durationMs); //$NON-NLS-1$
                result.addProperty("tail_log", tailLog(logFile, LOG_TAIL_LINES)); //$NON-NLS-1$
                if (exitCode != 0) {
                    result.addProperty("error_code", EdtToolErrorCode.PROCESS_START_FAILED.name()); //$NON-NLS-1$
                    result.addProperty("message", "YAXUnit process exited with a non-zero code"); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (report == null) {
                    result.addProperty("error_code", EdtToolErrorCode.INVALID_PATH.name()); //$NON-NLS-1$
                    result.addProperty("message", "JUnit XML report was not produced"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                result.addProperty("markdown_report", buildMarkdownReport(result)); //$NON-NLS-1$
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE, result);
            } catch (EdtToolException e) {
                return failure(opId, projectName, workspaceRoot, runDir, junitXmlFile, logFile, e.getCode(),
                        e.getMessage(), startTime);
            } catch (IOException e) {
                return failure(opId, projectName, workspaceRoot, runDir, junitXmlFile, logFile,
                        EdtToolErrorCode.PROCESS_START_FAILED, e.getMessage(), startTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(opId, projectName, workspaceRoot, runDir, junitXmlFile, logFile,
                        EdtToolErrorCode.PROCESS_TIMEOUT, "YAXUnit wait interrupted", startTime); //$NON-NLS-1$
            } catch (Exception e) {
                LOG.error("[%s] run_yaxunit_tests failed: %s", opId, e.getMessage(), e); //$NON-NLS-1$
                return failure(opId, projectName, workspaceRoot, runDir, junitXmlFile, logFile,
                        EdtToolErrorCode.PROCESS_START_FAILED, e.getMessage(), startTime);
            }
        });
    }

    protected File buildRunDirectory(File workspaceRoot, String opId) {
        File root = workspaceRoot == null
                ? new File(System.getProperty("java.io.tmpdir"), "codepilot1c-yaxunit/" + opId) //$NON-NLS-1$ //$NON-NLS-2$
                : new File(workspaceRoot, ".codepilot/runs/yaxunit/" + opId); //$NON-NLS-1$
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    protected File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null : ResourcesPlugin.getWorkspace().getRoot();
        return root == null || root.getLocation() == null ? null : root.getLocation().toFile();
    }

    private ToolResult failure(String opId, String projectName, File workspaceRoot, File runDir, File junitXmlFile,
            File logFile, EdtToolErrorCode code, String message, long startTime) {
        JsonObject result = errorPayload(opId, projectName, workspaceRoot, runDir, junitXmlFile, logFile, code,
                message);
        result.addProperty("duration_ms", System.currentTimeMillis() - startTime); //$NON-NLS-1$
        result.addProperty("tail_log", tailLog(logFile, LOG_TAIL_LINES)); //$NON-NLS-1$
        addEmptyReport(result);
        result.addProperty("markdown_report", buildMarkdownReport(result)); //$NON-NLS-1$
        return ToolResult.failure(pretty(result));
    }

    private static File writeParamsFile(File runDir, String filters, File junitXmlFile, boolean debug)
            throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("command", "RunUnitTests"); //$NON-NLS-1$ //$NON-NLS-2$
        params.addProperty("output_file", junitXmlFile.getAbsolutePath()); //$NON-NLS-1$
        params.addProperty("junit_xml_path", junitXmlFile.getAbsolutePath()); //$NON-NLS-1$
        params.addProperty("debug", debug); //$NON-NLS-1$
        if (filters != null && !filters.isBlank()) {
            params.addProperty("filter", filters); //$NON-NLS-1$
        }
        File paramsFile = new File(runDir, "yaxunit-params.json"); //$NON-NLS-1$
        Files.writeString(paramsFile.toPath(), pretty(params), StandardCharsets.UTF_8);
        return paramsFile;
    }

    static String buildStartupOption(File junitXmlFile, String filters, boolean debug) {
        StringBuilder option = new StringBuilder("RunUnitTests"); //$NON-NLS-1$
        option.append(";OutputFile=").append(junitXmlFile.getAbsolutePath()); //$NON-NLS-1$
        option.append(";JUnitXml=").append(junitXmlFile.getAbsolutePath()); //$NON-NLS-1$
        if (filters != null && !filters.isBlank()) {
            option.append(";Filter=").append(filters.trim()); //$NON-NLS-1$
        }
        if (debug) {
            option.append(";Debug"); //$NON-NLS-1$
        }
        return option.toString();
    }

    private static File resolveJunitXmlFile(File runDir, String requestedPath) {
        if (runDir == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "run directory is unavailable"); //$NON-NLS-1$
        }
        if (requestedPath == null || requestedPath.isBlank()) {
            return new File(runDir, "junit.xml"); //$NON-NLS-1$
        }
        Path raw = Path.of(requestedPath);
        if (raw.isAbsolute()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "junit_xml_path must be relative to the YAXUnit run directory"); //$NON-NLS-1$
        }
        Path runPath = runDir.toPath().toAbsolutePath().normalize();
        Path resolved = runPath.resolve(raw).normalize();
        if (!resolved.startsWith(runPath)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "junit_xml_path must stay within the YAXUnit run directory"); //$NON-NLS-1$
        }
        return resolved.toFile();
    }

    private static void ensureParentDirectory(File file) throws IOException {
        File parent = file == null ? null : file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath()); //$NON-NLS-1$
        }
    }

    private static QaJUnitReport parseReport(File junitXmlFile) {
        if (junitXmlFile == null || !junitXmlFile.isFile()) {
            return null;
        }
        try {
            return QaJUnitReport.parseDirectory(junitXmlFile.getParentFile(), MAX_FAILURE_DETAILS);
        } catch (IOException e) {
            LOG.warn("Failed to parse YAXUnit JUnit XML: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static String resolveStatus(int exitCode, QaJUnitReport report) {
        if (exitCode != 0 || report == null || report.failures + report.errors > 0) {
            return "failed"; //$NON-NLS-1$
        }
        return "completed"; //$NON-NLS-1$
    }

    private static void addReport(JsonObject result, QaJUnitReport report) {
        if (report == null) {
            addEmptyReport(result);
            return;
        }
        int failed = report.failures + report.errors;
        result.addProperty("tests_total", report.tests); //$NON-NLS-1$
        result.addProperty("tests_passed", Math.max(0, report.tests - failed - report.skipped)); //$NON-NLS-1$
        result.addProperty("tests_failed", failed); //$NON-NLS-1$
        result.addProperty("tests_skipped", report.skipped); //$NON-NLS-1$
        JsonArray violations = new JsonArray();
        if (report.failureDetails != null) {
            for (QaJUnitReport.FailureDetail detail : report.failureDetails) {
                JsonObject item = new JsonObject();
                item.addProperty("test", joinTestName(detail.className, detail.name)); //$NON-NLS-1$
                item.addProperty("type", safe(detail.type)); //$NON-NLS-1$
                item.addProperty("message", safe(detail.message)); //$NON-NLS-1$
                item.addProperty("file", safe(detail.file)); //$NON-NLS-1$
                item.addProperty("details", safe(detail.details)); //$NON-NLS-1$
                violations.add(item);
            }
        }
        result.add("violations", violations); //$NON-NLS-1$
        JsonArray files = new JsonArray();
        for (String file : report.files) {
            files.add(file);
        }
        result.add("junit_files", files); //$NON-NLS-1$
    }

    private static void addEmptyReport(JsonObject result) {
        result.addProperty("tests_total", 0); //$NON-NLS-1$
        result.addProperty("tests_passed", 0); //$NON-NLS-1$
        result.addProperty("tests_failed", 0); //$NON-NLS-1$
        result.addProperty("tests_skipped", 0); //$NON-NLS-1$
        result.add("violations", new JsonArray()); //$NON-NLS-1$
        result.add("junit_files", new JsonArray()); //$NON-NLS-1$
    }

    private static String buildMarkdownReport(JsonObject result) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# YAXUnit Test Report\n\n"); //$NON-NLS-1$
        markdown.append("- Status: ").append(jsonString(result, "status")).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
        markdown.append("- Project: ").append(jsonString(result, "project_name")).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
        markdown.append("- Duration: ").append(jsonLong(result, "duration_ms") / 1000.0).append(" s\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        markdown.append("- JUnit XML: ").append(jsonString(result, "junit_xml_path")).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        markdown.append("## Summary\n\n"); //$NON-NLS-1$
        markdown.append("| Total | Passed | Failed | Skipped |\n"); //$NON-NLS-1$
        markdown.append("| ---: | ---: | ---: | ---: |\n"); //$NON-NLS-1$
        markdown.append("| ")
                .append(jsonInt(result, "tests_total")).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(jsonInt(result, "tests_passed")).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(jsonInt(result, "tests_failed")).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(jsonInt(result, "tests_skipped")).append(" |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonArray violations = result.has("violations") && result.get("violations").isJsonArray() //$NON-NLS-1$ //$NON-NLS-2$
                ? result.getAsJsonArray("violations") //$NON-NLS-1$
                : new JsonArray();
        if (!violations.isEmpty()) {
            markdown.append("## Failures\n\n"); //$NON-NLS-1$
            for (int i = 0; i < violations.size(); i++) {
                JsonObject violation = violations.get(i).getAsJsonObject();
                markdown.append("### ").append(jsonString(violation, "test")).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
                markdown.append("- Type: ").append(jsonString(violation, "type")).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
                markdown.append("- Message: ").append(jsonString(violation, "message")).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                String details = jsonString(violation, "details"); //$NON-NLS-1$
                if (!details.isBlank()) {
                    markdown.append("```text\n").append(truncate(details, 800)).append("\n```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return markdown.toString();
    }

    private static JsonObject basePayload(String opId, String status, String projectName, File workspaceRoot,
            File runDir, File paramsFile, File junitXmlFile, File logFile, List<String> command,
            InfobaseReference infobase) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("status", status); //$NON-NLS-1$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("workspace_root", workspaceRoot == null ? "" : workspaceRoot.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("run_dir", runDir == null ? "" : runDir.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("params_path", paramsFile == null ? "" : paramsFile.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("junit_xml_path", junitXmlFile == null ? "" : junitXmlFile.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("log_path", logFile == null ? "" : logFile.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        if (infobase != null && infobase.getConnectionString() != null) {
            result.addProperty("infobase_connection", infobase.getConnectionString().asConnectionString()); //$NON-NLS-1$
        }
        result.add("command", toCommandJson(command)); //$NON-NLS-1$
        return result;
    }

    private static JsonObject errorPayload(String opId, String projectName, File workspaceRoot, File runDir,
            File junitXmlFile, File logFile, EdtToolErrorCode code, String message) {
        JsonObject result = basePayload(opId, "error", projectName, workspaceRoot, runDir, null, //$NON-NLS-1$
                junitXmlFile, logFile, List.of(), null);
        result.addProperty("error_code", code.name()); //$NON-NLS-1$
        result.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static int extractTimeoutSeconds(Map<String, Object> parameters, int defaultValue) {
        Object value = parameters == null ? null : parameters.get("timeout_s"); //$NON-NLS-1$
        if (value == null) {
            return defaultValue;
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

    private static void terminateProcessTree(Process process) throws InterruptedException {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle handle : descendants) {
            handle.destroy();
        }
        process.destroy();
        process.waitFor(5, TimeUnit.SECONDS);
        if (process.isAlive()) {
            for (ProcessHandle handle : descendants) {
                handle.destroyForcibly();
            }
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
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

    private static String joinTestName(String className, String name) {
        if (className == null || className.isBlank()) {
            return safe(name);
        }
        if (name == null || name.isBlank()) {
            return className;
        }
        return className + "." + name; //$NON-NLS-1$
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return safe(value);
        }
        return value.substring(0, maxChars) + "..."; //$NON-NLS-1$
    }

    private static String jsonString(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : ""; //$NON-NLS-1$
    }

    private static int jsonInt(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsInt()
                : 0;
    }

    private static long jsonLong(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsLong()
                : 0L;
    }

    private static String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
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
