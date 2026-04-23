package com.codepilot1c.core.settings;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.codepilot1c.core.agent.prompts.WorkspacePromptSourceResolver;

public class PromptTemplateServiceTest {

    @Test
    public void appliesFilesystemPromptOverridesWithModePrecedence() throws Exception {
        Path userHome = Files.createTempDirectory("prompt-home"); //$NON-NLS-1$
        Path projectRoot = Files.createTempDirectory("prompt-project"); //$NON-NLS-1$
        Files.createDirectories(userHome.resolve(".codepilot1c")); //$NON-NLS-1$
        Files.createDirectories(projectRoot.resolve(".codepilot1c")); //$NON-NLS-1$

        Files.writeString(userHome.resolve(".codepilot1c/system.md"), """
                ---
                mode: prepend
                ---
                USER
                """);
        Files.writeString(projectRoot.resolve(".codepilot1c/system.md"), """
                ---
                mode: append
                ---
                PROJECT
                """);
        Files.writeString(projectRoot.resolve(".codepilot1c/system-backend.md"), """
                ---
                mode: replace
                ---
                BACKEND
                """);

        PromptTemplateService service = new PromptTemplateService(
                new WorkspacePromptSourceResolver(projectRoot, userHome));

        assertEquals("USER\n\nBASE\n\nPROJECT", service.applyFilesystemSystemPromptOverride("BASE", false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BACKEND", service.applyFilesystemSystemPromptOverride("BASE", true)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
