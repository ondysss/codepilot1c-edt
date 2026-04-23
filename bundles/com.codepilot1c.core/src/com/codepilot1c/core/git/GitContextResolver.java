package com.codepilot1c.core.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Resolves Git context from explicit repo path, EDT project name, or current session/workspace state.
 */
final class GitContextResolver {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GitContextResolver.class);

    private final Supplier<Path> contextProjectPathResolver;
    private final Supplier<String> contextProjectNameResolver;

    GitContextResolver(Supplier<Path> contextProjectPathResolver, Supplier<String> contextProjectNameResolver) {
        this.contextProjectPathResolver = contextProjectPathResolver;
        this.contextProjectNameResolver = contextProjectNameResolver;
    }

    GitContextResolution resolveRepositoryContext(Path repoPath, String projectName) {
        String normalizedProjectName = normalize(projectName);
        if (normalizedProjectName != null) {
            return resolveFromProjectName(normalizedProjectName, repoPath);
        }
        if (repoPath != null) {
            return resolveFromRepoPath(repoPath);
        }
        GitContextResolution context = resolveDefaultProjectContext(true);
        if (context == null || context.projectPath() == null) {
            throw new GitToolException(GitErrorCode.INVALID_ARGUMENT,
                    "project_name or repo_path is required to resolve git context"); //$NON-NLS-1$
        }
        return new GitContextResolution(context.projectName(), context.projectPath(), context.projectPath(), null,
                context.resolutionSource());
    }

    private GitContextResolution resolveFromProjectName(String projectName, Path repoPath) {
        WorkspaceProject project = requireWorkspaceProject(projectName);
        Path candidatePath = repoPath == null
                ? project.path()
                : resolveAgainstBase(repoPath, project.path());
        String source = repoPath == null ? "explicit_project_name" : "project_name_plus_repo_path"; //$NON-NLS-1$ //$NON-NLS-2$
        return new GitContextResolution(project.name(), project.path(), candidatePath, null, source);
    }

    private GitContextResolution resolveFromRepoPath(Path repoPath) {
        if (repoPath.isAbsolute()) {
            Path normalized = repoPath.toAbsolutePath().normalize();
            WorkspaceProject relatedProject = findRelatedProject(normalized);
            return new GitContextResolution(
                    relatedProject == null ? null : relatedProject.name(),
                    relatedProject == null ? null : relatedProject.path(),
                    normalized,
                    null,
                    "explicit_repo_path"); //$NON-NLS-1$
        }

        GitContextResolution context = resolveDefaultProjectContext(true);
        if (context == null || context.projectPath() == null) {
            Path normalized = repoPath.toAbsolutePath().normalize();
            return new GitContextResolution(null, null, normalized, null, "explicit_repo_path"); //$NON-NLS-1$
        }
        Path candidatePath = context.projectPath().resolve(repoPath).normalize();
        return new GitContextResolution(context.projectName(), context.projectPath(), candidatePath, null,
                context.resolutionSource() + "_repo_path"); //$NON-NLS-1$
    }

    private GitContextResolution resolveDefaultProjectContext(boolean failOnAmbiguity) {
        String contextProjectName = normalize(resolveContextProjectName());
        if (contextProjectName != null) {
            WorkspaceProject project = findWorkspaceProjectByName(contextProjectName);
            if (project != null) {
                return new GitContextResolution(project.name(), project.path(), project.path(), null,
                        "context_project_name"); //$NON-NLS-1$
            }
        }

        Path contextProjectPath = normalizeExistingPath(resolveContextProjectPath());
        if (contextProjectPath != null) {
            WorkspaceProject relatedProject = findRelatedProject(contextProjectPath);
            if (relatedProject != null) {
                return new GitContextResolution(relatedProject.name(), relatedProject.path(), relatedProject.path(), null,
                        "context_project_path"); //$NON-NLS-1$
            }
            return new GitContextResolution(null, contextProjectPath, contextProjectPath, null, "context_project_path"); //$NON-NLS-1$
        }

        List<WorkspaceProject> projects = listOpenWorkspaceProjects();
        if (projects.isEmpty()) {
            return null;
        }
        if (projects.size() == 1) {
            WorkspaceProject project = projects.get(0);
            return new GitContextResolution(project.name(), project.path(), project.path(), null,
                    "single_workspace_project"); //$NON-NLS-1$
        }
        if (failOnAmbiguity) {
            throw new GitToolException(GitErrorCode.GIT_CONTEXT_AMBIGUOUS,
                    "Multiple open EDT projects found; pass project_name or repo_path explicitly: " //$NON-NLS-1$
                            + joinProjectNames(projects));
        }
        return null;
    }

    private WorkspaceProject requireWorkspaceProject(String projectName) {
        WorkspaceProject project = findWorkspaceProjectByName(projectName);
        if (project != null) {
            return project;
        }
        throw new GitToolException(GitErrorCode.PROJECT_NOT_FOUND, "EDT project not found: " + projectName); //$NON-NLS-1$
    }

    private WorkspaceProject findWorkspaceProjectByName(String projectName) {
        IWorkspaceRoot root = workspaceRoot();
        if (root == null) {
            return null;
        }
        IProject direct = root.getProject(projectName);
        WorkspaceProject directProject = asWorkspaceProject(direct);
        if (directProject != null) {
            return directProject;
        }
        WorkspaceProject insensitiveMatch = null;
        for (IProject project : root.getProjects()) {
            WorkspaceProject candidate = asWorkspaceProject(project);
            if (candidate == null || !candidate.name().equalsIgnoreCase(projectName)) {
                continue;
            }
            if (insensitiveMatch != null) {
                return null;
            }
            insensitiveMatch = candidate;
        }
        return insensitiveMatch;
    }

    private WorkspaceProject findRelatedProject(Path candidatePath) {
        List<WorkspaceProject> projects = listOpenWorkspaceProjects();
        return projects.stream()
                .filter(project -> isRelated(candidatePath, project.path()))
                .max(Comparator.comparingInt(project -> Math.max(project.path().getNameCount(), 0)))
                .orElse(null);
    }

    private List<WorkspaceProject> listOpenWorkspaceProjects() {
        List<WorkspaceProject> result = new ArrayList<>();
        IWorkspaceRoot root = workspaceRoot();
        if (root == null) {
            return result;
        }
        for (IProject project : root.getProjects()) {
            WorkspaceProject resolved = asWorkspaceProject(project);
            if (resolved != null) {
                result.add(resolved);
            }
        }
        return result;
    }

    private static boolean isRelated(Path left, Path right) {
        if (left == null || right == null) {
            return false;
        }
        return left.startsWith(right) || right.startsWith(left);
    }

    private static Path resolveAgainstBase(Path repoPath, Path basePath) {
        if (repoPath == null) {
            return basePath == null ? null : basePath.toAbsolutePath().normalize();
        }
        if (repoPath.isAbsolute()) {
            return repoPath.toAbsolutePath().normalize();
        }
        if (basePath != null) {
            return basePath.resolve(repoPath).normalize();
        }
        return repoPath.toAbsolutePath().normalize();
    }

    private static WorkspaceProject asWorkspaceProject(IProject project) {
        if (project == null || !project.exists() || !project.isOpen()) {
            return null;
        }
        IPath location = project.getLocation();
        if (location == null) {
            return null;
        }
        Path path = normalizeExistingPath(Path.of(location.toOSString()));
        if (path == null) {
            return null;
        }
        return new WorkspaceProject(project.getName(), path);
    }

    private static Path normalizeExistingPath(Path path) {
        if (path == null) {
            return null;
        }
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return Files.isDirectory(normalized) ? normalized : null;
        } catch (RuntimeException e) {
            LOG.debug("Git context path normalization failed: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Path resolveContextProjectPath() {
        try {
            return contextProjectPathResolver == null ? null : contextProjectPathResolver.get();
        } catch (RuntimeException e) {
            LOG.debug("Git context project path resolution failed: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private String resolveContextProjectName() {
        try {
            return contextProjectNameResolver == null ? null : contextProjectNameResolver.get();
        } catch (RuntimeException e) {
            LOG.debug("Git context project name resolution failed: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static IWorkspaceRoot workspaceRoot() {
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            return workspace == null ? null : workspace.getRoot();
        } catch (RuntimeException e) {
            LOG.debug("Git workspace access failed: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static String joinProjectNames(List<WorkspaceProject> projects) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < projects.size(); i++) {
            if (i > 0) {
                result.append(", "); //$NON-NLS-1$
            }
            result.append(projects.get(i).name());
        }
        return result.toString();
    }

    private record WorkspaceProject(String name, Path path) {
    }
}
