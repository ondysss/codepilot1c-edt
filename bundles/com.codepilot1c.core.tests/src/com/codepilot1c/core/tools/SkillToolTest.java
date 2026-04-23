package com.codepilot1c.core.tools;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

public class SkillToolTest {

    @Test
    public void listAndLookupReturnDiscoveredSkills() throws Exception {
        Path projectRoot = Files.createTempDirectory("skill-tool-project"); //$NON-NLS-1$
        Path userHome = Files.createTempDirectory("skill-tool-home"); //$NON-NLS-1$
        Files.createDirectories(projectRoot.resolve(".codepilot/skills/local")); //$NON-NLS-1$
        Files.writeString(projectRoot.resolve(".codepilot/skills/local/SKILL.md"), """
                ---
                name: local
                description: Local project skill
                ---
                local body
                """);

        String previousUserDir = System.getProperty("user.dir"); //$NON-NLS-1$
        String previousUserHome = System.getProperty("user.home"); //$NON-NLS-1$
        try {
            System.setProperty("user.dir", projectRoot.toString()); //$NON-NLS-1$
            System.setProperty("user.home", userHome.toString()); //$NON-NLS-1$

            SkillTool tool = new SkillTool();
            ToolResult listResult = tool.execute(Map.of("list", true)).join(); //$NON-NLS-1$
            ToolResult lookupResult = tool.execute(Map.of("name", "local")).join(); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(listResult.isSuccess());
            assertTrue(listResult.getContent().contains("local: Local project skill")); //$NON-NLS-1$
            assertTrue(lookupResult.isSuccess());
            assertTrue(lookupResult.getContent().contains("local body")); //$NON-NLS-1$
        } finally {
            restoreProperty("user.dir", previousUserDir); //$NON-NLS-1$
            restoreProperty("user.home", previousUserHome); //$NON-NLS-1$
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
