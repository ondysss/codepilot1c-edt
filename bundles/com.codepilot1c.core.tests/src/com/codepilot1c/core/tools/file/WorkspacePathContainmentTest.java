package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.GsdShipPathPolicy;

public class WorkspacePathContainmentTest {

    @Test
    public void releasePolicyRejectsAliasesAbsoluteAndAlternateSeparatorTraversal() {
        assertTrue(GsdShipPathPolicy.isReleaseArtifactPath("CHANGELOG.md")); //$NON-NLS-1$
        assertTrue(GsdShipPathPolicy.isReleaseArtifactPath(
                "docs\\release-notes\\v1.json")); //$NON-NLS-1$
        assertFalse(GsdShipPathPolicy.isReleaseArtifactPath(
                "docs/release-notes/../v1.md")); //$NON-NLS-1$
        assertFalse(GsdShipPathPolicy.isReleaseArtifactPath("/CHANGELOG.md")); //$NON-NLS-1$
        assertFalse(GsdShipPathPolicy.isReleaseArtifactPath(
                "C:\\workspace\\CHANGELOG.md")); //$NON-NLS-1$
        assertFalse(GsdShipPathPolicy.isReleaseArtifactPath(
                "docs//release-notes/v1.md")); //$NON-NLS-1$
    }

    @Test
    public void physicalContainmentRejectsLinkedAndSymlinkedOutsideTargets() throws Exception {
        Path workspace = Files.createTempDirectory("ship-workspace-"); //$NON-NLS-1$
        Path project = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path outside = Files.createTempDirectory("ship-outside-"); //$NON-NLS-1$
        Path inside = Files.createDirectories(project.resolve("docs/release-notes")); //$NON-NLS-1$

        assertTrue(WorkspacePathContainment.isContained(
                workspace, project, inside.resolve("v1.md"))); //$NON-NLS-1$
        assertFalse(WorkspacePathContainment.isContained(
                workspace, project, outside.resolve("v1.md"))); //$NON-NLS-1$

        Path link = project.resolve("release-notes"); //$NON-NLS-1$
        Files.createSymbolicLink(link, outside);
        assertFalse(WorkspacePathContainment.isContained(
                workspace, project, link.resolve("v2.md"))); //$NON-NLS-1$

        Path source = Files.writeString(project.resolve("Main.java"), "source"); //$NON-NLS-1$ //$NON-NLS-2$
        Path releaseAlias = project.resolve("CHANGELOG.md"); //$NON-NLS-1$
        Files.createSymbolicLink(releaseAlias, source);
        assertFalse(WorkspacePathContainment.isContained(
                workspace, project, releaseAlias));
    }

    @Test
    public void physicalContainmentRejectsProjectOutsideWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("ship-workspace-"); //$NON-NLS-1$
        Path externalProject = Files.createTempDirectory("ship-project-"); //$NON-NLS-1$
        assertFalse(WorkspacePathContainment.isContained(
                workspace, externalProject, externalProject.resolve("CHANGELOG.md"))); //$NON-NLS-1$
    }
}
