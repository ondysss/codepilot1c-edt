package com.codepilot1c.core.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.tools.workspace.WorkspaceImportProjectTool;
import com.codepilot1c.core.workspace.WorkspaceImportErrorCode;
import com.codepilot1c.core.workspace.WorkspaceImportException;
import com.codepilot1c.core.workspace.WorkspaceProjectImportResult;
import com.codepilot1c.core.workspace.WorkspaceProjectImportService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class WorkspaceImportProjectToolTest {

    @Test
    public void returnsStructuredSuccessPayload() {
        WorkspaceImportProjectTool tool = new WorkspaceImportProjectTool(new WorkspaceProjectImportService() {
            @Override
            public WorkspaceProjectImportResult importProject(Path projectPath, boolean openProject,
                    boolean refreshProject) {
                return new WorkspaceProjectImportResult("Demo", projectPath.toString(), true, openProject, refreshProject); //$NON-NLS-1$
            }
        });

        ToolResult result = tool.execute(Map.of(
                "path", "/tmp/demo", //$NON-NLS-1$ //$NON-NLS-2$
                "open", Boolean.TRUE, //$NON-NLS-1$
                "refresh", Boolean.FALSE //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue("imported".equals(json.get("status").getAsString())); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("created").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("opened").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.get("refreshed").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void returnsStructuredErrorPayload() {
        WorkspaceImportProjectTool tool = new WorkspaceImportProjectTool(new WorkspaceProjectImportService() {
            @Override
            public WorkspaceProjectImportResult importProject(Path projectPath, boolean openProject,
                    boolean refreshProject) {
                throw new WorkspaceImportException(WorkspaceImportErrorCode.IMPORT_FAILED, "boom"); //$NON-NLS-1$
            }
        });

        ToolResult result = tool.execute(Map.of("path", "/tmp/demo")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertTrue("IMPORT_FAILED".equals(json.get("error_code").getAsString())); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
