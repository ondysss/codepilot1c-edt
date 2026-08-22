/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import org.eclipse.core.resources.IResource;

import com.codepilot1c.core.filesystem.SecureDirectoryMutation;
import com.codepilot1c.core.filesystem.SecureDirectoryMutation.CapabilityPolicy;

/** Physical/canonical containment checks for workspace writes. */
final class WorkspacePathContainment {

    private WorkspacePathContainment() {
    }

    static boolean isContained(Path workspace, Path project, Path target) {
        if (workspace == null || project == null || target == null) {
            return false;
        }
        try {
            Path projectAbsolute = project.toAbsolutePath().normalize();
            Path targetAbsolute = target.toAbsolutePath().normalize();
            if (!targetAbsolute.startsWith(projectAbsolute)
                    || targetAbsolute.equals(projectAbsolute)) {
                return false;
            }
            Path workspaceReal = workspace.toRealPath();
            Path projectReal = project.toRealPath();
            Path targetReal = resolveRealPathIncludingMissingTail(target);
            Path expectedTarget = projectReal.resolve(
                    projectAbsolute.relativize(targetAbsolute)).normalize();
            return projectReal.startsWith(workspaceReal)
                    && targetReal.startsWith(projectReal)
                    && targetReal.equals(expectedTarget);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Rejects both directly linked Eclipse resources and resources below a
     * linked container. This is independent of filesystem canonicalization.
     */
    static boolean isLinkedResource(IResource resource) {
        return resource == null || resource.isLinked(IResource.CHECK_ANCESTORS);
    }

    /**
     * Publishes a ship artifact through an open parent-directory handle. The hook is intentionally
     * between initial validation and boundary revalidation so tests can deterministically replace
     * ancestry without timing races.
     */
    static void writeContained(Path workspace, Path project, Path target, byte[] bytes,
            SecureDirectoryMutation.MutationHook hook) throws IOException {
        writeContained(workspace, project, target, bytes, hook,
                CapabilityPolicy.REQUIRE_SECURE);
    }

    static void writeContained(Path workspace, Path project, Path target, byte[] bytes,
            SecureDirectoryMutation.MutationHook hook, CapabilityPolicy policy)
            throws IOException {
        if (!isContained(workspace, project, target) || target.getParent() == null) {
            throw new java.nio.file.AccessDeniedException(target.toString(), null,
                    "ship artifact target is not physically contained"); //$NON-NLS-1$
        }
        SecureDirectoryMutation.BoundRoot boundProject =
                SecureDirectoryMutation.bindRoot(workspace, project, hook, policy,
                        "ship-project-bind"); //$NON-NLS-1$
        // Capability comes first: a non-secure provider cannot be repaired by
        // precreating the requested parent directory.
        SecureDirectoryMutation.requireSecureDirectoryStreams(boundProject);
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            if (hook != null) {
                hook.beforeMutation("ship-parent-create"); //$NON-NLS-1$
            }
            throw new com.codepilot1c.core.filesystem.SecureDirectoryCapabilityException(
                    target.getParent(),
                    "secure parent-directory creation is unavailable on Java 17; " //$NON-NLS-1$
                            + "pre-create this exact directory inside the project"); //$NON-NLS-1$
        }
        try (SecureDirectoryMutation parent = SecureDirectoryMutation.open(
                boundProject, target.getParent(), hook, "ship-parent-bind")) { //$NON-NLS-1$
            parent.atomicWrite(target.getFileName().toString(), bytes, "ship-artifact"); //$NON-NLS-1$
        }
    }

    private static Path resolveRealPathIncludingMissingTail(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Deque<Path> missing = new ArrayDeque<>();
        Path existing = absolute;
        while (existing != null
                && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) {
                missing.addFirst(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("No existing path ancestor"); //$NON-NLS-1$
        }
        Path resolved = existing.toRealPath();
        for (Path segment : missing) {
            resolved = resolved.resolve(segment);
        }
        return resolved.normalize();
    }
}
