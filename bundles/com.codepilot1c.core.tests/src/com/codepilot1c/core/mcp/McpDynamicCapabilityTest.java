package com.codepilot1c.core.mcp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.mcp.config.McpServerConfig;
import com.codepilot1c.core.mcp.model.McpTool;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class McpDynamicCapabilityTest {

    @Test
    public void remoteAnnotationsNeverLowerUntrustedMcpRisk() {
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(new McpTool("unknown", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(toolWith("readOnlyHint", true))); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(toolWith("destructiveHint", true))); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(toolWith("readOnlyHint", false))); //$NON-NLS-1$

        McpTool contradictory = toolWith("readOnlyHint", true); //$NON-NLS-1$
        contradictory.getAnnotations().addProperty("destructiveHint", true); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(contradictory));

        McpTool invalid = new McpTool("invalid", ""); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject invalidAnnotations = new JsonObject();
        invalidAnnotations.addProperty("readOnlyHint", "yes"); //$NON-NLS-1$ //$NON-NLS-2$
        invalid.setAnnotations(invalidAnnotations);
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(invalid));
    }

    @Test
    public void localExactTrustMayLowerRiskUnlessRemoteHintsRaiseIt() {
        assertEquals(DynamicToolCapability.READ_ONLY,
                McpToolAdapter.dynamicToolCapabilityOf(
                        new McpTool("trusted", ""), true)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DynamicToolCapability.READ_ONLY,
                McpToolAdapter.dynamicToolCapabilityOf(
                        toolWith("readOnlyHint", true), true)); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(
                        toolWith("readOnlyHint", false), true)); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(
                        toolWith("destructiveHint", true), true)); //$NON-NLS-1$
        McpTool contradictory = toolWith("readOnlyHint", true); //$NON-NLS-1$
        contradictory.getAnnotations().addProperty("destructiveHint", true); //$NON-NLS-1$
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(contradictory, true));
    }

    @Test
    public void realMcpReadOnlyHintRemainsMutatingWithoutLocalTrust() {
        McpTool tool = new Gson().fromJson("""
                {
                  "name": "search_issues",
                  "description": "Search",
                  "inputSchema": {"type": "object"},
                  "annotations": {"readOnlyHint": true}
                }
                """, McpTool.class);
        assertEquals(DynamicToolCapability.MUTATING,
                McpToolAdapter.dynamicToolCapabilityOf(tool));
        assertEquals(DynamicToolCapability.READ_ONLY,
                McpToolAdapter.dynamicToolCapabilityOf(tool, true));
    }

    @Test
    public void trustedReadOnlyToolNamesRoundTripInLocalServerConfiguration() {
        McpServerConfig config = McpServerConfig.builder()
                .name("local") //$NON-NLS-1$
                .command("server") //$NON-NLS-1$
                .trustedReadOnlyTools(java.util.List.of(
                        "search_issues", " search_issues ", "get_docs")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .build();

        McpServerConfig restored = McpServerConfig.fromJson(config.toJson());

        assertEquals(java.util.List.of("search_issues", "get_docs"), //$NON-NLS-1$ //$NON-NLS-2$
                restored.getTrustedReadOnlyTools());
        assertEquals(true, restored.isTrustedReadOnlyTool("search_issues")); //$NON-NLS-1$
        assertEquals(false, restored.isTrustedReadOnlyTool("Search_Issues")); //$NON-NLS-1$
    }

    private static McpTool toolWith(String annotation, boolean value) {
        McpTool tool = new McpTool("annotated", ""); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject annotations = new JsonObject();
        annotations.addProperty(annotation, value);
        tool.setAnnotations(annotations);
        return tool;
    }
}
