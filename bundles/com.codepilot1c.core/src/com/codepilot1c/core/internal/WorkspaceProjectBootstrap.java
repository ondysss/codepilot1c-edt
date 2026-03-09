package com.codepilot1c.core.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Imports externally located EDT projects into the current Eclipse workspace.
 */
final class WorkspaceProjectBootstrap {

    static final String IMPORT_PROJECTS_PROPERTY = "codepilot1c.workspace.importProjects"; //$NON-NLS-1$

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(WorkspaceProjectBootstrap.class);

    private WorkspaceProjectBootstrap() {
    }

    static void importConfiguredProjects() {
        String raw = System.getProperty(IMPORT_PROJECTS_PROPERTY); //$NON-NLS-1$
        if (raw == null || raw.isBlank()) {
            return;
        }
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        if (workspace == null) {
            LOG.warn("Workspace import skipped: ResourcesPlugin workspace is unavailable"); //$NON-NLS-1$
            return;
        }
        Set<Path> projectPaths = parseProjectPaths(raw);
        if (projectPaths.isEmpty()) {
            return;
        }
        try {
            workspace.run(monitor -> {
                for (Path projectPath : projectPaths) {
                    importSingleProject(workspace, projectPath);
                }
            }, new NullProgressMonitor());
        } catch (CoreException e) {
            LOG.error("Failed to import configured workspace projects", e); //$NON-NLS-1$
        }
    }

    private static Set<Path> parseProjectPaths(String raw) {
        Set<Path> result = new LinkedHashSet<>();
        for (String token : raw.split(java.io.File.pathSeparator)) {
            String trimmed = token == null ? "" : token.trim(); //$NON-NLS-1$
            if (trimmed.isEmpty()) {
                continue;
            }
            result.add(Path.of(trimmed).toAbsolutePath().normalize());
        }
        return result;
    }

    private static void importSingleProject(IWorkspace workspace, Path projectPath) {
        Path projectFile = projectPath.resolve(".project"); //$NON-NLS-1$
        if (!Files.isDirectory(projectPath) || !Files.isRegularFile(projectFile)) {
            LOG.warn("Skipping workspace import for %s: .project not found", projectPath); //$NON-NLS-1$
            return;
        }
        try {
            IProjectDescription description = workspace.loadProjectDescription(IPath.fromPath(projectFile));
            if (description == null || description.getName() == null || description.getName().isBlank()) {
                LOG.warn("Skipping workspace import for %s: failed to read project description", projectPath); //$NON-NLS-1$
                return;
            }
            String projectName = description.getName();
            IWorkspaceRoot root = workspace.getRoot();
            IProject project = root.getProject(projectName);
            IPath locationPath = IPath.fromPath(projectPath);
            if (!project.exists()) {
                description.setLocation(locationPath);
                project.create(description, new NullProgressMonitor());
                LOG.info("Imported workspace project %s from %s", projectName, projectPath); //$NON-NLS-1$
            }
            if (!project.isOpen()) {
                project.open(new NullProgressMonitor());
            }
            project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
        } catch (CoreException e) {
            LOG.error(String.format("Failed to import workspace project from %s", projectPath), e); //$NON-NLS-1$
        }
    }
}
