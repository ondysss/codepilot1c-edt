package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.tools.diagnostics.AnalyzeToolErrorTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AnalyzeToolErrorToolTest {

    @Test
    public void analyzesEdtRuntimeErrorAndSuggestsQaInspectStatus() throws Exception {
        File workspaceRoot = Files.createTempDirectory("analyze-tool-error").toFile(); //$NON-NLS-1$
        File logFile = new File(workspaceRoot, ".codepilot/runs/edt_diagnostics/op-1/launch.log"); //$NON-NLS-1$
        logFile.getParentFile().mkdirs();
        Files.writeString(logFile.toPath(), "line1\nline2\nline3\n", StandardCharsets.UTF_8); //$NON-NLS-1$

        AnalyzeToolErrorTool tool = new TestAnalyzeToolErrorTool(workspaceRoot);
        String rawError = """
                {
                  "status": "error",
                  "error_code": "LAUNCH_CONFIG_NOT_FOUND",
                  "message": "RuntimeClient launch configuration is missing",
                  "project_name": "Demo",
                  "log_path": "%s"
                }
                """.formatted(logFile.getAbsolutePath().replace("\\", "\\\\")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        ToolResult result = tool.execute(Map.of(
                "tool_name", "edt_diagnostics", //$NON-NLS-1$ //$NON-NLS-2$
                "tool_result", rawError, //$NON-NLS-1$
                "max_log_lines", Integer.valueOf(2) //$NON-NLS-1$
        )).join();

        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("LAUNCH_CONFIG_NOT_FOUND", json.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("recognized").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("summary").getAsString().contains("RuntimeClient")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("log_tail").getAsString().contains("line2")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonArray calls = json.getAsJsonArray("suggested_tool_calls"); //$NON-NLS-1$
        assertEquals(1, calls.size());
        JsonObject call = calls.get(0).getAsJsonObject();
        assertEquals("qa_inspect", call.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("status", call.getAsJsonObject("arguments").get("command").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void analyzesLegacyBracketError() {
        AnalyzeToolErrorTool tool = new TestAnalyzeToolErrorTool(new File(System.getProperty("java.io.tmpdir"))); //$NON-NLS-1$

        ToolResult result = tool.execute(Map.of(
                "tool_name", "edt_diagnostics", //$NON-NLS-1$ //$NON-NLS-2$
                "tool_result", "Error: [PROJECT_NOT_FOUND] EDT project not found: Demo" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("PROJECT_NOT_FOUND", json.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("summary").getAsString().contains("workspace")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static class TestAnalyzeToolErrorTool extends AnalyzeToolErrorTool {
        private final File workspaceRoot;

        TestAnalyzeToolErrorTool(File workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        protected File getWorkspaceRoot() {
            return workspaceRoot;
        }
    }
}
