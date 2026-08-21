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
