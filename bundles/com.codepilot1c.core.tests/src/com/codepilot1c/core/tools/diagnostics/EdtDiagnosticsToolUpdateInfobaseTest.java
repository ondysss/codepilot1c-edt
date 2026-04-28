package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;

public class EdtDiagnosticsToolUpdateInfobaseTest {

    @Test
    public void updateInfobaseCommandForwardsRuntimeParametersToDelegate() {
        CapturingTool updateInfobase = new CapturingTool("update_infobase-result"); //$NON-NLS-1$
        EdtDiagnosticsTool tool = new EdtDiagnosticsTool(
                new CapturingTool("metadata-smoke"), //$NON-NLS-1$
                new CapturingTool("trace-export"), //$NON-NLS-1$
                new CapturingTool("analyze-error"), //$NON-NLS-1$
                updateInfobase,
                new CapturingTool("launch-app")); //$NON-NLS-1$

        ToolResult result = tool.execute(Map.of(
                "command", "update_infobase", //$NON-NLS-1$ //$NON-NLS-2$
                "project_name", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "async", Boolean.TRUE, //$NON-NLS-1$
                "keep_connected", Boolean.FALSE //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        assertEquals("update_infobase-result", result.getContent()); //$NON-NLS-1$
        assertEquals("DemoConfiguration", updateInfobase.captured.get("project_name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(Boolean.TRUE, updateInfobase.captured.get("async")); //$NON-NLS-1$
        assertSame(Boolean.FALSE, updateInfobase.captured.get("keep_connected")); //$NON-NLS-1$
    }

    private static final class CapturingTool implements ITool {
        private final String content;
        private Map<String, Object> captured;

        private CapturingTool(String content) {
            this.content = content;
        }

        @Override
        public String getName() {
            return "capturing_tool"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "Captures parameters for tests."; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            captured = parameters;
            return CompletableFuture.completedFuture(ToolResult.success(content));
        }
    }
}
