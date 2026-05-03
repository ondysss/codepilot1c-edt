package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Ignore;
import org.junit.Test;

import com.codepilot1c.core.skills.SkillCatalog;

// Depends on Eclipse Platform OSGi (SystemPromptAssembler → SessionManager.<clinit> calls Platform.getLog)
// which is unavailable in a plain Maven JUnit run. Run as Eclipse JUnit Plug-in Test.
@Ignore("requires Eclipse Platform OSGi — run as Eclipse JUnit Plug-in Test") //$NON-NLS-1$
public class SystemPromptAssemblerTest {

    @Test
    public void assembledPromptIncludesLayeredContextAndRequestedSkillsWithProvenance() throws Exception {
        Path userHome = Files.createTempDirectory("assembler-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("assembler-project"); //$NON-NLS-1$
        Files.createDirectories(userHome.resolve(".codepilot")); //$NON-NLS-1$
        Files.writeString(userHome.resolve(".codepilot/AGENTS.md"), "user-agents-layer"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("AGENTS.md"), "project-agents-layer"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("Code.md"), "project-code-layer"); //$NON-NLS-1$ //$NON-NLS-2$

        SystemPromptAssembler assembler = new SystemPromptAssembler(
                new InstructionContextService(projectRoot, userHome),
                new SkillCatalog(projectRoot, userHome));

        SystemPromptAssembler.PromptAssembly nonBackend = assembler.assembleDetailed(
                "BASE", "BASE", "build", List.of("review", "explain"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(1, countOccurrences(nonBackend.prompt(), "BASE")); //$NON-NLS-1$
        assertTrue(nonBackend.prompt().contains("Source: " + userHome.resolve(".codepilot/AGENTS.md").toAbsolutePath())); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(nonBackend.prompt().contains("project-agents-layer")); //$NON-NLS-1$
        assertTrue(nonBackend.prompt().contains("Skill: review")); //$NON-NLS-1$
        assertTrue(!nonBackend.prompt().contains("Skill: explain")); //$NON-NLS-1$

        SystemPromptAssembler.PromptAssembly backend = assembler.assembleDetailed(
                "BASE", null, "build", List.of("review", "explain"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(backend.prompt().contains("project-code-layer")); //$NON-NLS-1$
        assertTrue(backend.prompt().contains("Skill: explain")); //$NON-NLS-1$
    }

    @Test
    public void promptProviderUsesSameAssemblyPathAsDirectAssembler() {
        String assembled = SystemPromptAssembler.getInstance().assemble(
                AgentPromptTemplates.buildBuildPrompt(),
                null,
                "build", //$NON-NLS-1$
                List.of());
        String fromProvider = new com.codepilot1c.core.mcp.host.prompt.PromptTemplateProvider()
                .getPrompt("build", java.util.Map.of()) //$NON-NLS-1$
                .orElseThrow()
                .getMessages()
                .get(0)
                .getContent()
                .getText();

        assertEquals(assembled, fromProvider);
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
