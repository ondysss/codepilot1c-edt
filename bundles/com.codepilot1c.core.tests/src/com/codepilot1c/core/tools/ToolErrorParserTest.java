package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ToolErrorParserTest {

    @Test
    public void parsesStructuredJsonErrorCodePayload() {
        ToolErrorParser.ParsedToolError parsed = ToolErrorParser.parse("""
                {
                  "status": "error",
                  "error_code": "LAUNCH_CONFIG_NOT_FOUND",
                  "message": "Launch config is missing",
                  "project_name": "Demo",
                  "log_path": "/tmp/demo.log",
                  "op_id": "op-1"
                }
                """); //$NON-NLS-1$

        assertEquals("LAUNCH_CONFIG_NOT_FOUND", parsed.errorCode()); //$NON-NLS-1$
        assertEquals("Launch config is missing", parsed.message()); //$NON-NLS-1$
        assertEquals("Demo", parsed.projectName()); //$NON-NLS-1$
        assertEquals("/tmp/demo.log", parsed.logPath()); //$NON-NLS-1$
        assertEquals("json_error_code", parsed.format()); //$NON-NLS-1$
    }

    @Test
    public void parsesBracketErrorWithLlmPrefix() {
        ToolErrorParser.ParsedToolError parsed = ToolErrorParser
                .parse("Error: [PROJECT_NOT_FOUND] EDT project not found: Demo"); //$NON-NLS-1$

        assertEquals("PROJECT_NOT_FOUND", parsed.errorCode()); //$NON-NLS-1$
        assertEquals("EDT project not found: Demo", parsed.message()); //$NON-NLS-1$
        assertEquals("bracket", parsed.format()); //$NON-NLS-1$
    }

    @Test
    public void parsesColonErrorFormat() {
        ToolErrorParser.ParsedToolError parsed = ToolErrorParser
                .parse("QA_STEPS_SEARCH_ERROR: query is required"); //$NON-NLS-1$

        assertEquals("QA_STEPS_SEARCH_ERROR", parsed.errorCode()); //$NON-NLS-1$
        assertEquals("query is required", parsed.message()); //$NON-NLS-1$
        assertEquals("colon", parsed.format()); //$NON-NLS-1$
        assertNull(parsed.logPath());
    }
}
