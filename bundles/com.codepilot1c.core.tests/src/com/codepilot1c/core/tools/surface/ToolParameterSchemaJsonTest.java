package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Every parameter schema the MCP host can publish must be strict JSON with an object root.
 *
 * <p>Inside a Java text block {@code \"} is a Java escape and yields a bare quote, and the host swallows the resulting
 * parse error (see {@link ToolSchemaJsonChecker}): {@code tools/list} then carries
 * {@code "Schema parse error: ... Unterminated object at line 76 column 193 path $.properties.properties.description"}
 * in place of the tool's arguments, and nothing else fails. The first test covers the raw schema of every
 * registered tool; the second covers what the surface publishes for each profile, which for some tools is an override
 * text the raw schema never shows.
 */
public class ToolParameterSchemaJsonTest {

    /** Far below the registered count: a registry that failed to initialize must not pass by checking nothing. */
    private static final int MIN_TOOLS = 50;

    @Test
    public void everyRegisteredToolSchemaIsStrictJsonObject() {
        List<String> violations = new ArrayList<>();
        Set<String> checked = new TreeSet<>();
        for (ITool tool : ToolRegistry.getInstance().getAllTools()) {
            checked.add(tool.getName());
            String violation = ToolSchemaJsonChecker.violation(tool.getParameterSchema());
            if (violation != null) {
                violations.add(tool.getName() + ": " + violation); //$NON-NLS-1$
            }
        }
        assertCheckedEnough(checked);
        assertTrue("Tool schemas that MCP clients receive as \"Schema parse error\":\n" //$NON-NLS-1$
                + String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void everyPublishedToolDefinitionSchemaIsStrictJsonObject() {
        ToolRegistry registry = ToolRegistry.getInstance();
        Map<String, AgentProfile> profiles = new LinkedHashMap<>();
        AgentProfile defaultProfile = ToolSurfaceContext.defaultProfile();
        profiles.put(String.valueOf(defaultProfile.getId()), defaultProfile);
        for (AgentProfile profile : AgentProfileRegistry.getInstance().getAllProfiles()) {
            profiles.putIfAbsent(String.valueOf(profile.getId()), profile);
        }
        List<String> violations = new ArrayList<>();
        Set<String> checked = new TreeSet<>();
        for (Map.Entry<String, AgentProfile> profile : profiles.entrySet()) {
            for (ToolDefinition definition : registry.getToolDefinitions(profile.getValue())) {
                checked.add(definition.getName());
                String violation = ToolSchemaJsonChecker.violation(definition.getParametersSchema());
                if (violation != null) {
                    violations.add(profile.getKey() + "/" + definition.getName() + ": " + violation); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        assertCheckedEnough(checked);
        assertTrue("Published tool schemas that MCP clients receive as \"Schema parse error\":\n" //$NON-NLS-1$
                + String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void checkerRejectsTextBlockQuoteThatEndsTheDescription() {
        // A \" written inside a text block schema, in miniature.
        String schema = "{\"type\":\"object\",\"properties\":{\"properties\":{\"type\":\"object\"," //$NON-NLS-1$
                + "\"description\":\"напр. {\"repeatPeriodInDay\":300}\"}}}"; //$NON-NLS-1$
        assertViolation(schema, "Unterminated object"); //$NON-NLS-1$
    }

    @Test
    public void checkerRejectsSyntaxOnlyLenientParsingAccepts() {
        assertViolation("{\"type\":\"object\",\"required\":[\"a\",]}", null); //$NON-NLS-1$
        assertViolation("{\"type\":object}", null); //$NON-NLS-1$
        assertViolation("{\"type\":\"object\"} {}", null); //$NON-NLS-1$
    }

    @Test
    public void checkerRejectsDuplicateMemberName() {
        assertViolation("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"a\":{\"type\":\"integer\"}}}", //$NON-NLS-1$
                "Duplicate member name at $.properties.a"); //$NON-NLS-1$
    }

    @Test
    public void checkerRejectsNonObjectRoot() {
        assertViolation("[]", null); //$NON-NLS-1$
        assertViolation("{\"type\":\"array\"}", "\"type\" must be \"object\""); //$NON-NLS-1$ //$NON-NLS-2$
        assertViolation("{\"properties\":{}}", "\"type\" must be \"object\""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void checkerAcceptsEscapedQuotesAndBlankSchema() {
        assertNull(ToolSchemaJsonChecker.violation("{\"type\":\"object\",\"properties\":{\"p\":{\"type\":\"object\"," //$NON-NLS-1$
                + "\"description\":\"напр. {\\\"repeatPeriodInDay\\\":300}\"}},\"required\":[\"p\"]}")); //$NON-NLS-1$
        assertNull(ToolSchemaJsonChecker.violation(" ")); //$NON-NLS-1$
        assertNull(ToolSchemaJsonChecker.violation(null));
    }

    private static void assertViolation(String schema, String expectedFragment) {
        String violation = ToolSchemaJsonChecker.violation(schema);
        assertNotNull("Accepted: " + schema, violation); //$NON-NLS-1$
        if (expectedFragment != null) {
            assertTrue(violation, violation.contains(expectedFragment));
        }
    }

    private static void assertCheckedEnough(Set<String> checked) {
        assertTrue("create_metadata was not checked: " + checked, checked.contains("create_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Only " + checked.size() + " tools checked, registry did not initialize: " + checked, //$NON-NLS-1$ //$NON-NLS-2$
                checked.size() >= MIN_TOOLS);
    }
}
