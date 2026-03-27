package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.tools.workspace.ImportProjectFromInfobaseTool;
import com.codepilot1c.core.diagnostics.DiagnosticsService.DiagnosticsSummary;
import com.codepilot1c.core.edt.imports.EdtProjectImportService;
import com.codepilot1c.core.edt.imports.ImportProjectFromInfobaseRequest;
import com.codepilot1c.core.edt.imports.ImportProjectFromInfobaseResult;
import com.codepilot1c.core.edt.imports.StandaloneServerImportInfo;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ImportProjectFromInfobaseToolTest {

    @Test
    public void returnsStructuredSuccessPayload() {
        ImportProjectFromInfobaseTool tool = new ImportProjectFromInfobaseTool(new EdtProjectImportService() {
            @Override
            public ImportProjectFromInfobaseResult importProject(String opId, ImportProjectFromInfobaseRequest request) {
                return new ImportProjectFromInfobaseResult(
                        opId,
                        "completed", //$NON-NLS-1$
                        request.normalizedSourceProjectName(),
                        request.normalizedTargetProjectName(),
                        "/tmp/ImportedProject", //$NON-NLS-1$
                        "8.3.25", //$NON-NLS-1$
                        "/tmp/export", //$NON-NLS-1$
                        false,
                        new StandaloneServerImportInfo(true, true, "started", "srv", "8.3.25", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                "/tmp/server", "/tmp/server-data", "http://localhost:1541", "e1cib://designer"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        new DiagnosticsSummary("project", request.normalizedTargetProjectName(), 0, 1, 0, List.of())); //$NON-NLS-1$
            }
        });

        ToolResult result = tool.execute(Map.of(
                "source_project_name", "Source", //$NON-NLS-1$ //$NON-NLS-2$
                "target_project_name", "ImportedProject" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("completed", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ImportedProject", json.get("targetProjectName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.getAsJsonObject("standalone").get("started").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void returnsStructuredErrorPayload() {
        ImportProjectFromInfobaseTool tool = new ImportProjectFromInfobaseTool(new EdtProjectImportService() {
            @Override
            public ImportProjectFromInfobaseResult importProject(String opId, ImportProjectFromInfobaseRequest request) {
                throw new EdtToolException(EdtToolErrorCode.PROJECT_IMPORT_FAILED, "boom"); //$NON-NLS-1$
            }
        });

        ToolResult result = tool.execute(Map.of(
                "source_project_name", "Source", //$NON-NLS-1$ //$NON-NLS-2$
                "target_project_name", "ImportedProject" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals("PROJECT_IMPORT_FAILED", json.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
