package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.tools.bsl.ValidateQueryTool;

public class ValidateQueryToolSchemaTest {

    private final ValidateQueryTool tool = new ValidateQueryTool();

    @Test
    public void exposesValidateQueryName() {
        assertEquals("validate_query", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void categorizedAsBslReadOnlyTool() {
        assertEquals("bsl", tool.getCategory()); //$NON-NLS-1$
        assertTrue(tool.getTags().contains("read-only")); //$NON-NLS-1$
        assertTrue(tool.getTags().contains("edt")); //$NON-NLS-1$
        assertFalse(tool.isMutating());
    }

    @Test
    public void schemaDeclaresQueryParameters() {
        String schema = tool.getParameterSchema();
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"queryText\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"dcsMode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    @Test
    public void hasDescription() {
        assertFalse(tool.getDescription().isBlank());
    }
}
