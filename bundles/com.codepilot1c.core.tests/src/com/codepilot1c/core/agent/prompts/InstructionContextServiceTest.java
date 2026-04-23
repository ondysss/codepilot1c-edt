package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class InstructionContextServiceTest {

    @Test
    public void loadsLayeredAgentsAndCodeWithExpectedPrecedence() throws Exception {
        Path userHome = Files.createTempDirectory("instruction-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("instruction-project"); //$NON-NLS-1$
        Path nestedProject = Files.createDirectories(projectRoot.resolve("module/submodule")); //$NON-NLS-1$

        Files.createDirectories(userHome.resolve(".codepilot")); //$NON-NLS-1$
        Files.writeString(userHome.resolve(".codepilot/AGENTS.md"), "user-agents"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(userHome.resolve(".codepilot1c")); //$NON-NLS-1$
        Files.writeString(userHome.resolve(".codepilot1c/AGENTS.md"), "user-agents-v2"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(projectRoot.resolve("AGENTS.md"), "root-agents"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(projectRoot.resolve(".codepilot")); //$NON-NLS-1$
        Files.writeString(projectRoot.resolve(".codepilot/Code.md"), "root-code"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(projectRoot.resolve(".codepilot1c")); //$NON-NLS-1$
        Files.writeString(projectRoot.resolve(".codepilot1c/Code.md"), "root-code-v2"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(nestedProject.resolve("Code.md"), "nested-code"); //$NON-NLS-1$ //$NON-NLS-2$

        InstructionContextService service = new InstructionContextService(nestedProject, userHome);

        List<InstructionContextService.InstructionLayer> agentsLayers = service.loadAgentsLayers();
        assertEquals(3, agentsLayers.size());
        assertEquals("user-agents", agentsLayers.get(0).content()); //$NON-NLS-1$
        assertEquals("user-agents-v2", agentsLayers.get(1).content()); //$NON-NLS-1$
        assertEquals("root-agents", agentsLayers.get(2).content()); //$NON-NLS-1$

        assertTrue(service.loadCodeLayers(false).isEmpty());

        List<InstructionContextService.InstructionLayer> codeLayers = service.loadCodeLayers(true);
        assertEquals(3, codeLayers.size());
        assertEquals("root-code", codeLayers.get(0).content()); //$NON-NLS-1$
        assertEquals("root-code-v2", codeLayers.get(1).content()); //$NON-NLS-1$
        assertEquals("nested-code", codeLayers.get(2).content()); //$NON-NLS-1$
    }
}
