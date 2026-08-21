/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.profiles.InitAgentProfile;
import com.codepilot1c.core.memory.project.ProjectMemoryInitializationService.Mode;
import com.codepilot1c.core.memory.project.ProjectMemoryInitializationService.Request;
import com.codepilot1c.core.memory.project.ProjectMemoryInitializationService.Status;

public class ProjectMemoryInitializationServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createLaunchesInitProfileAndRequiresFilePostCheck() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        CapturingLauncher launcher = new CapturingLauncher(
                CompletableFuture.completedFuture(AgentResult.success("ok", Collections.emptyList(), 1, 0, 5))); //$NON-NLS-1$
        ProjectMemoryInitializationService service =
                new ProjectMemoryInitializationService(new ProjectMemoryContextService(), launcher);

        ProjectMemoryInitializationService.Result result = service.initialize(new Request(
                Mode.CREATE,
                root,
                "DemoProject", //$NON-NLS-1$
                "DemoProject/Code.md", "session-a")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(InitAgentProfile.ID, launcher.profileId);
        assertTrue(launcher.prompt.contains("DemoProject/Code.md")); //$NON-NLS-1$
        assertTrue(launcher.prompt.contains("write_file")); //$NON-NLS-1$
        assertTrue(launcher.prompt.contains("overwrite=true")); //$NON-NLS-1$
        assertTrue(launcher.prompt.contains("не больше 8")); //$NON-NLS-1$
        assertTrue(launcher.prompt.contains("до финального ответа")); //$NON-NLS-1$
        assertFalse(launcher.prompt.contains("delegate_to_agent")); //$NON-NLS-1$
        assertFalse(launcher.prompt.contains("task(")); //$NON-NLS-1$
        assertEquals(Status.CODE_MD_NOT_WRITTEN, result.getStatus());
    }

    @Test
    public void agentSuccessReturnsSuccessOnlyAfterMemoryFileExists() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        CapturingLauncher launcher = new CapturingLauncher(null) {
            @Override
            public CompletableFuture<AgentResult> launch(String prompt, String profileId) {
                super.launch(prompt, profileId);
                try {
                    Files.writeString(root.resolve("Code.md"), "project memory", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
                return CompletableFuture.completedFuture(AgentResult.success("ok", Collections.emptyList(), 1, 1, 5)); //$NON-NLS-1$
            }
        };
        ProjectMemoryInitializationService service =
                new ProjectMemoryInitializationService(new ProjectMemoryContextService(), launcher);

        ProjectMemoryInitializationService.Result result = service.initialize(new Request(
                Mode.CREATE,
                root,
                "DemoProject", //$NON-NLS-1$
                "DemoProject/Code.md", "session-a")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Status.SUCCESS, result.getStatus());
        assertEquals(root.resolve("Code.md"), result.getSourcePath());
    }

    @Test
    public void emptyMemoryFileIsNotSuccessfulInitialization() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        CapturingLauncher launcher = new CapturingLauncher(null) {
            @Override
            public CompletableFuture<AgentResult> launch(String prompt, String profileId) {
                super.launch(prompt, profileId);
                try {
                    Files.writeString(root.resolve("Code.md"), "", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
                return CompletableFuture.completedFuture(AgentResult.success("ok", Collections.emptyList(), 1, 1, 5)); //$NON-NLS-1$
            }
        };
        ProjectMemoryInitializationService service =
                new ProjectMemoryInitializationService(new ProjectMemoryContextService(), launcher);

        ProjectMemoryInitializationService.Result result = service.initialize(new Request(
                Mode.CREATE,
                root,
                "DemoProject", //$NON-NLS-1$
                "DemoProject/Code.md", "session-a")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Status.CODE_MD_NOT_WRITTEN, result.getStatus());
        assertEquals(root.resolve("Code.md"), result.getSourcePath());
    }

    @Test
    public void updateRequiresExistingMemoryFileToChange() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();
        Path codeMd = root.resolve("Code.md"); //$NON-NLS-1$
        Files.writeString(codeMd, "old project memory", StandardCharsets.UTF_8); //$NON-NLS-1$
        CapturingLauncher launcher = new CapturingLauncher(
                CompletableFuture.completedFuture(AgentResult.success("ok", Collections.emptyList(), 1, 0, 5))); //$NON-NLS-1$
        ProjectMemoryInitializationService service =
                new ProjectMemoryInitializationService(new ProjectMemoryContextService(), launcher);

        ProjectMemoryInitializationService.Result result = service.initialize(new Request(
                Mode.UPDATE,
                root,
                "DemoProject", //$NON-NLS-1$
                "DemoProject/Code.md", "session-a")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Status.CODE_MD_NOT_WRITTEN, result.getStatus());
        assertEquals(codeMd, result.getSourcePath());
    }

    @Test
    public void launcherFailureMapsBusyAndProviderStatuses() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath();

        ProjectMemoryInitializationService busyService = new ProjectMemoryInitializationService(
                new ProjectMemoryContextService(),
                (prompt, profileId) -> CompletableFuture.failedFuture(
                        new IllegalStateException("Agent session is already running"))); //$NON-NLS-1$
        ProjectMemoryInitializationService providerService = new ProjectMemoryInitializationService(
                new ProjectMemoryContextService(),
                (prompt, profileId) -> CompletableFuture.failedFuture(
                        new IllegalStateException("LLM-провайдер не настроен"))); //$NON-NLS-1$

        Request request = new Request(
                Mode.UPDATE, root, "DemoProject", "DemoProject/Code.md", "session-a"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(Status.AGENT_BUSY, busyService.initialize(request).join().getStatus());
        assertEquals(Status.PROVIDER_UNAVAILABLE, providerService.initialize(request).join().getStatus());
    }

    @Test
    public void cancellingInitializationCancelsOnlyItsLauncherHandle() throws Exception {
        Path root = temporaryFolder.newFolder("project").toPath(); //$NON-NLS-1$
        CancelTrackingFuture launcherFuture = new CancelTrackingFuture();
        ProjectMemoryInitializationService service = new ProjectMemoryInitializationService(
                new ProjectMemoryContextService(), (prompt, profileId) -> launcherFuture);

        CompletableFuture<ProjectMemoryInitializationService.Result> initialization = service.initialize(new Request(
                Mode.CREATE, root, "DemoProject", "DemoProject/Code.md", "session-a")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(initialization.cancel(true));
        assertTrue(initialization.isCancelled());
        assertTrue(launcherFuture.cancelled);
    }

    @Test
    public void twoViewsLaunchWithTheirOwnProjectAndSessionIdentity() throws Exception {
        Path firstRoot = temporaryFolder.newFolder("first-project").toPath(); //$NON-NLS-1$
        Path secondRoot = temporaryFolder.newFolder("second-project").toPath(); //$NON-NLS-1$
        IdentityCapturingLauncher launcher = new IdentityCapturingLauncher();
        ProjectMemoryInitializationService service = new ProjectMemoryInitializationService(
                new ProjectMemoryContextService(), launcher);

        service.initialize(new Request(
                Mode.CREATE, firstRoot, "First", "First/Code.md", "view-session-1")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        service.initialize(new Request(
                Mode.CREATE, secondRoot, "Second", "Second/Code.md", "view-session-2")).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(List.of(
                firstRoot.toAbsolutePath().normalize() + "|view-session-1", //$NON-NLS-1$
                secondRoot.toAbsolutePath().normalize() + "|view-session-2"), //$NON-NLS-1$
                launcher.identities);
    }

    @Test
    public void missingOwningSessionFailsClosedWithoutLaunchingAgent() throws Exception {
        Path root = temporaryFolder.newFolder("unbound-project").toPath(); //$NON-NLS-1$
        CapturingLauncher launcher = new CapturingLauncher(
                CompletableFuture.completedFuture(AgentResult.success(
                        "unexpected", Collections.emptyList(), 1, 0, 1))); //$NON-NLS-1$
        ProjectMemoryInitializationService service = new ProjectMemoryInitializationService(
                new ProjectMemoryContextService(), launcher);

        ProjectMemoryInitializationService.Result result = service.initialize(new Request(
                Mode.CREATE, root, "Unbound", "Unbound/Code.md")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Status.AGENT_FAILED, result.getStatus());
        assertEquals(null, launcher.prompt);
    }

    private static final class IdentityCapturingLauncher
            implements ProjectMemoryInitializationService.AgentLauncher {
        private final List<String> identities = new java.util.ArrayList<>();

        @Override
        public CompletableFuture<AgentResult> launch(String prompt, String profileId) {
            throw new AssertionError("identity-aware launch expected"); //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<AgentResult> launch(String prompt, String profileId,
                String projectPath, String sessionId) {
            identities.add(projectPath + "|" + sessionId); //$NON-NLS-1$
            return CompletableFuture.completedFuture(
                    AgentResult.success("ok", Collections.emptyList(), 1, 0, 1)); //$NON-NLS-1$
        }
    }

    private static final class CancelTrackingFuture extends CompletableFuture<AgentResult> {
        private boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static class CapturingLauncher implements ProjectMemoryInitializationService.AgentLauncher {

        private final CompletableFuture<AgentResult> result;
        private String prompt;
        private String profileId;

        private CapturingLauncher(CompletableFuture<AgentResult> result) {
            this.result = result;
        }

        @Override
        public CompletableFuture<AgentResult> launch(String prompt, String profileId) {
            this.prompt = prompt;
            this.profileId = profileId;
            return result;
        }
    }
}
