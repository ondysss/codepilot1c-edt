package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.BuildAgentProfile;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ToolRegistry;

public class ToolDescriptionCoverageTest {

    @Test
    public void buildSurfaceToolsExposeNonEmptyDescriptions() {
        ToolRegistry registry = ToolRegistry.getInstance();
        List<ToolDefinition> definitions = registry.getToolDefinitions(
                registry.createRuntimeSurfaceContext(new BuildAgentProfile()));

        List<String> missingDescriptions = definitions.stream()
                .filter(definition -> definition.getDescription() == null || definition.getDescription().isBlank())
                .map(ToolDefinition::getName)
                .sorted()
                .collect(Collectors.toList());

        assertTrue("Tools without descriptions: " + missingDescriptions, missingDescriptions.isEmpty()); //$NON-NLS-1$
    }
}
