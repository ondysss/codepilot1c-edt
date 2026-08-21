package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager;

/**
 * Shared resolution of the EDT project a tool should act on when {@code projectName} is omitted:
 * the active editor project (from the current session), or — if exactly one project is open — that
 * project. Used by read-only tools to avoid forcing the agent to repeat the project name.
 */
public final class ActiveProjectSupport {

    private ActiveProjectSupport() {
    }

    /**
     * @return the active editor project, or the single open project, or {@code null} when the
     *     project cannot be determined unambiguously (zero or multiple open projects, no session).
     */
    public static IProject resolveActiveProject() {
        return resolveActiveProject(ToolExecutionContext.unscoped());
    }

    /**
     * Resolves the project for a tool execution. An explicit per-view identity
     * is authoritative: if it cannot be resolved, this method returns null
     * instead of falling through to another view's global current session.
     */
    public static IProject resolveActiveProject(ToolExecutionContext context) {
        ToolExecutionContext effective = context != null ? context : ToolExecutionContext.unscoped();
        if (effective.hasProjectIdentity()) {
            String path = effective.projectPath();
            if (path.isBlank() && !effective.sessionId().isBlank()) {
                path = SessionManager.getInstance().loadSession(effective.sessionId())
                        .map(Session::getProjectPath)
                        .orElse(""); //$NON-NLS-1$
            }
            return resolveProjectByPath(path);
        }
        try {
            Session session = SessionManager.getInstance().getOrCreateCurrentSession();
            IProject project = session != null ? resolveProjectByPath(session.getProjectPath()) : null;
            if (project != null) {
                return project;
            }
        } catch (Exception e) {
            // Fall through to the single-open-project heuristic.
        }
        List<IProject> open = openProjects();
        return open.size() == 1 ? open.get(0) : null;
    }

    /** @return the resolved active project name, or {@code null}. */
    public static String resolveActiveProjectName() {
        IProject project = resolveActiveProject();
        return project != null ? project.getName() : null;
    }

    /** @return project name resolved from an explicit execution identity, or {@code null}. */
    public static String resolveActiveProjectName(ToolExecutionContext context) {
        IProject project = resolveActiveProject(context);
        return project != null ? project.getName() : null;
    }

    /** Returns the captured/resolved project path without consulting another view's session. */
    public static String resolveProjectPath(ToolExecutionContext context) {
        ToolExecutionContext effective = context != null ? context : ToolExecutionContext.unscoped();
        if (effective.hasProjectIdentity()) {
            if (!effective.projectPath().isBlank()) {
                return effective.projectPath();
            }
            return SessionManager.getInstance().loadSession(effective.sessionId())
                    .map(Session::getProjectPath)
                    .orElse(null);
        }
        IProject project = resolveActiveProject(effective);
        return project != null && project.getLocation() != null
                ? project.getLocation().toOSString() : null;
    }

    private static IProject resolveProjectByPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }
        try {
            IProject project = SessionManager.getInstance().findProjectByPath(projectPath);
            return project != null && project.exists() && project.isOpen() ? project : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return all open projects in the workspace (never {@code null}). */
    public static List<IProject> openProjects() {
        List<IProject> result = new ArrayList<>();
        try {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.exists() && project.isOpen()) {
                    result.add(project);
                }
            }
        } catch (Exception e) {
            // Workspace unavailable; return what we have.
        }
        return result;
    }

    /** Comma-separated names of open projects, for actionable error messages. */
    public static String openProjectNames() {
        List<IProject> open = openProjects();
        return open.isEmpty()
                ? "(no open projects)" //$NON-NLS-1$
                : open.stream().map(IProject::getName).collect(Collectors.joining(", ")); //$NON-NLS-1$
    }
}
