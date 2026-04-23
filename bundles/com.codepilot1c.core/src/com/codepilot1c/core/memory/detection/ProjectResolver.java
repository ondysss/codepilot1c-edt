/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.detection;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;

/**
 * Resolves the active 1C project for memory context injection.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>Explicit project path from session</li>
 *   <li>First open project in workspace (fallback)</li>
 * </ol>
 *
 * <p>This is a simplified resolver. Future versions will integrate with
 * the active editor selection via UI bundle contribution.</p>
 */
public final class ProjectResolver {

    private static final ILog LOG = Platform.getLog(ProjectResolver.class);

    private ProjectResolver() {
    }

    /**
     * Resolves an {@link IProject} from the given project path.
     *
     * @param projectPath absolute filesystem path to the project, may be null
     * @return the resolved project, or null if not found or path is null
     */
    public static IProject resolve(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null; // IMPORTANT #6 fix: no fallback to random project
        }

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects()) {
            if (!project.exists() || !project.isOpen()) {
                continue;
            }
            IPath location = project.getLocation();
            if (location != null && location.toOSString().equals(projectPath)) {
                return project;
            }
        }

        LOG.info("ProjectResolver: no project found for path: " + projectPath); //$NON-NLS-1$
        return null; // IMPORTANT #6 fix: no fallback to first open project
    }

    /**
     * Returns the project path string for the given project.
     *
     * @param project the Eclipse project
     * @return absolute path string, or null
     */
    public static String toProjectPath(IProject project) {
        if (project == null || !project.exists()) {
            return null;
        }
        IPath location = project.getLocation();
        return location != null ? location.toOSString() : null;
    }

    /**
     * Fallback: returns the first open project in the workspace.
     */
    private static IProject resolveFirstOpenProject() {
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            for (IProject project : root.getProjects()) {
                if (project.exists() && project.isOpen()) {
                    return project;
                }
            }
        } catch (Exception e) {
            LOG.warn("ProjectResolver: workspace not available", e); //$NON-NLS-1$
        }
        return null;
    }
}
