package com.codepilot1c.core.mcp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.mcp.model.McpTool;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class McpDynamicCapabilityTest {

    @Test
    public void standardMcpAnnotationsDistinguishReadOnlyMutatingAndUnknown() {
        assertEquals(DynamicToolCapability.NONE,
                McpToolAdapter.dynamicToolCapabilityOf(new McpTool("unknown", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DynamicToolCapability.READ_ONLY,
                McpToolAdapter.dynamicToolCapabilityOf(toolWith("readOnlyHint", true))); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(toolWith("destructiveHint", true))); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(toolWith("readOnlyHint", false))); //$NON-NLS-1$

        McpTool contradictory = toolWith("readOnlyHint", true); //$NON-NLS-1$
        contradictory.getAnnotations().addProperty("destructiveHint", true); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.NONE,
                McpToolAdapter.dynamicToolCapabilityOf(contradictory));

        McpTool invalid = new McpTool("invalid", ""); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject invalidAnnotations = new JsonObject();
        invalidAnnotations.addProperty("readOnlyHint", "yes"); //$NON-NLS-1$ //$NON-NLS-2$
        invalid.setAnnotations(invalidAnnotations);
        assertEquals(DynamicToolCapability.NONE,
                McpToolAdapter.dynamicToolCapabilityOf(invalid));
    }

    @Test
    public void annotationsDeserializeFromRealMcpToolShape() {
        McpTool tool = new Gson().fromJson("""
                {
                  "name": "search_issues",
                  "description": "Search",
                  "inputSchema": {"type": "object"},
                  "annotations": {"readOnlyHint": true}
                }
                """, McpTool.class);
        assertEquals(DynamicToolCapability.READ_ONLY,
                McpToolAdapter.dynamicToolCapabilityOf(tool));
    }

    private static McpTool toolWith(String annotation, boolean value) {
        McpTool tool = new McpTool("annotated", ""); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject annotations = new JsonObject();
        annotations.addProperty(annotation, value);
        tool.setAnnotations(annotations);
        return tool;
    }
}
