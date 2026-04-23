package com.codepilot1c.core.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class SkillCatalogTest {

    @Test
    public void projectSkillsOverrideUserAndBundledDefinitions() throws Exception {
        Path projectRoot = Files.createTempDirectory("skill-project"); //$NON-NLS-1$
        Path userHome = Files.createTempDirectory("skill-home"); //$NON-NLS-1$

        Path projectSkillDir = Files.createDirectories(projectRoot.resolve(".codepilot/skills/review")); //$NON-NLS-1$
        Files.writeString(projectSkillDir.resolve("SKILL.md"), """
                ---
                name: review
                description: Project override
                allowed-tools: [read_file]
                backend-only: false
                ---
                project review body
                """);

        Path projectSkillDirV2 = Files.createDirectories(projectRoot.resolve(".codepilot1c/skills/review")); //$NON-NLS-1$
        Files.writeString(projectSkillDirV2.resolve("SKILL.md"), """
                ---
                name: review
                description: Project override v2
                allowed-tools: [read_file, grep]
                backend-only: false
                ---
                project review body v2
                """);

        Path userSkillDir = Files.createDirectories(userHome.resolve(".codepilot/skills/custom")); //$NON-NLS-1$
        Files.writeString(userSkillDir.resolve("SKILL.md"), """
                ---
                name: custom
                description: User custom skill
                allowed-tools: [glob, grep]
                backend-only: false
                ---
                user custom body
                """);

        Path userSkillDirV2 = Files.createDirectories(userHome.resolve(".codepilot1c/skills/custom")); //$NON-NLS-1$
        Files.writeString(userSkillDirV2.resolve("SKILL.md"), """
                ---
                name: custom
                description: User custom skill v2
                allowed-tools: [glob]
                backend-only: false
                ---
                user custom body v2
                """);

        SkillCatalog catalog = new SkillCatalog(projectRoot, userHome);

        SkillDefinition review = catalog.getSkill("review", true).orElseThrow(); //$NON-NLS-1$
        assertEquals("Project override v2", review.description()); //$NON-NLS-1$
        assertEquals(SkillDefinition.SourceType.PROJECT, review.sourceType());

        SkillDefinition custom = catalog.getSkill("custom", true).orElseThrow(); //$NON-NLS-1$
        assertEquals(SkillDefinition.SourceType.USER, custom.sourceType());
        assertEquals(List.of("glob"), custom.allowedTools()); //$NON-NLS-1$
    }

    @Test
    public void backendOnlySkillsAreHiddenForNonBackendProviders() {
        SkillCatalog catalog = new SkillCatalog();

        assertTrue(catalog.getSkill("explain", true).isPresent()); //$NON-NLS-1$
        assertTrue(catalog.discoverVisibleSkills(true).stream().anyMatch(skill -> "explain".equals(skill.name()))); //$NON-NLS-1$

        assertFalse(catalog.getSkill("explain", false).isPresent()); //$NON-NLS-1$
        assertFalse(catalog.discoverVisibleSkills(false).stream().anyMatch(skill -> "explain".equals(skill.name()))); //$NON-NLS-1$
    }
}
