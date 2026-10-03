package com.codepilot1c.core.tools;

import static org.junit.Assert.*;
import org.junit.Test;
import com.google.gson.JsonParser;

/** Also run against the installed baseline: it must fail on the missing extension tool. */
public class EdtUpdateExtensionSurfaceTest {
    @Test public void registryExposesDedicatedExtensionReloadTool() {
        ITool tool=ToolRegistry.getInstance().getTool("edt_update_extension");
        assertNotNull("Missing EDT extension update tool",tool);
        assertTrue(tool.requiresConfirmation());assertTrue(tool.isDestructive());
        var schema=JsonParser.parseString(tool.getParameterSchema()).getAsJsonObject();
        assertEquals("extension_project",schema.getAsJsonArray("required").get(0).getAsString());
        assertTrue(schema.getAsJsonObject("properties").has("dry_run"));
    }
}
