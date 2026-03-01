/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.handlers;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.tm.terminal.view.core.interfaces.constants.ITerminalsConnectorConstants;
import org.eclipse.tm.terminal.view.ui.interfaces.ILauncherDelegate;
import org.eclipse.tm.terminal.view.ui.launcher.LauncherDelegateManager;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import com.codepilot1c.core.settings.VibePreferenceConstants;

/**
 * Handler for opening the OS shell terminal view.
 */
public class OpenTerminalHandler extends AbstractHandler {

    private static final String TERMINAL_VIEW_ID = "org.eclipse.tm.terminal.view.ui.TerminalsView"; //$NON-NLS-1$
    private static final String TERMINAL_BUNDLE_ID = "org.eclipse.tm.terminal.view.ui"; //$NON-NLS-1$
    private static final String LOCAL_LAUNCHER_ID = "org.eclipse.tm.terminal.connector.local.launcher.local"; //$NON-NLS-1$
    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            return null;
        }

        if (Platform.getBundle(TERMINAL_BUNDLE_ID) == null) {
            MessageDialog.openError(
                    window.getShell(),
                    "Терминал недоступен",
                    "Не найден пакет TM Terminal. Добавьте его в target/установку EDT и повторите.");
            return null;
        }

        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }

        try {
            page.showView(TERMINAL_VIEW_ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Failed to open Terminal view", e); //$NON-NLS-1$
        }

        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
        String cwdMode = prefs.get(VibePreferenceConstants.PREF_TERMINAL_CWD_MODE, "project"); //$NON-NLS-1$
        boolean noColor = prefs.getBoolean(VibePreferenceConstants.PREF_TERMINAL_NO_COLOR, false);
        String titlePrefix = prefs.get(VibePreferenceConstants.PREF_TERMINAL_TITLE_PREFIX, ""); //$NON-NLS-1$

        TerminalLaunchContext context = resolveLaunchContext(event, cwdMode);
        ISelection selection = context.selection();
        IPath workingDir = context.workingDir();
        ILauncherDelegate delegate = findLocalLauncher(selection);
        if (delegate == null) {
            MessageDialog.openError(
                    window.getShell(),
                    "Терминал недоступен",
                    "Не найден локальный лаунчер TM Terminal. Проверьте установку компонентов.");
            return null;
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put("delegateId", delegate.getId()); //$NON-NLS-1$
        properties.put("selection", selection); //$NON-NLS-1$
        properties.put(ITerminalsConnectorConstants.PROP_FORCE_NEW, Boolean.TRUE);
        if (noColor) {
            Map<String, String> env = new HashMap<>();
            env.put("NO_COLOR", "1"); //$NON-NLS-1$ //$NON-NLS-2$
            properties.put(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT, env);
            properties.put(ITerminalsConnectorConstants.PROP_PROCESS_MERGE_ENVIRONMENT, Boolean.TRUE);
        }
        if (workingDir != null) {
            properties.put(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR, workingDir.toOSString());
            String title = buildTitle(workingDir, titlePrefix);
            if (title != null) {
                properties.put(ITerminalsConnectorConstants.PROP_TITLE, title);
            }
        }
        delegate.execute(properties, null);

        return null;
    }

    private static TerminalLaunchContext resolveLaunchContext(ExecutionEvent event, String cwdMode) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        IStructuredSelection structured = (selection instanceof IStructuredSelection s && !s.isEmpty()) ? s : null;

        IResource resource = null;
        if (structured != null) {
            Object element = structured.getFirstElement();
            if (element instanceof IResource res) {
                resource = res;
            } else if (element instanceof IAdaptable adaptable) {
                resource = adaptable.getAdapter(IResource.class);
            } else if (element instanceof IPath path) {
                return new TerminalLaunchContext(structured, normalizeDir(path));
            }
        }

        IEditorInput editorInput = HandlerUtil.getActiveEditorInput(event);
        if (editorInput instanceof IFileEditorInput fileInput) {
            resource = fileInput.getFile();
        } else if (editorInput instanceof IPathEditorInput pathInput) {
            IPath path = pathInput.getPath();
            if (path != null && resource == null) {
                resource = null;
                structured = new StructuredSelection(path);
                return new TerminalLaunchContext(structured, normalizeDir(path));
            }
        }

        IProject project = resolveProject(resource);
        IPath workingDir = resolveWorkingDir(cwdMode, resource, project);
        ISelection resolvedSelection = resolveSelectionForMode(cwdMode, structured, resource, project);

        return new TerminalLaunchContext(resolvedSelection, workingDir);
    }

    private static ISelection resolveSelectionForMode(String cwdMode,
            IStructuredSelection structured,
            IResource resource,
            IProject project) {
        if ("project".equals(cwdMode)) { //$NON-NLS-1$
            if (project != null) {
                return new StructuredSelection(project);
            }
        }
        if ("workspace".equals(cwdMode)) { //$NON-NLS-1$
            if (ResourcesPlugin.getWorkspace() != null && ResourcesPlugin.getWorkspace().getRoot() != null) {
                return new StructuredSelection(ResourcesPlugin.getWorkspace().getRoot());
            }
        }
        if ("selection".equals(cwdMode)) { //$NON-NLS-1$
            if (structured != null) {
                return structured;
            }
            if (resource != null) {
                return new StructuredSelection(resource);
            }
        }
        if (resource != null) {
            return new StructuredSelection(resource);
        }
        if (project != null) {
            return new StructuredSelection(project);
        }
        return StructuredSelection.EMPTY;
    }

    private static IPath resolveWorkingDir(String cwdMode, IResource resource, IProject project) {
        if ("home".equals(cwdMode)) { //$NON-NLS-1$
            return normalizeDir(new org.eclipse.core.runtime.Path(System.getProperty("user.home"))); //$NON-NLS-1$
        }
        if ("workspace".equals(cwdMode)) { //$NON-NLS-1$
            return workspaceLocation();
        }
        if ("selection".equals(cwdMode)) { //$NON-NLS-1$
            if (resource != null && resource.getLocation() != null) {
                return normalizeDir(resource.getLocation());
            }
        }
        if ("project".equals(cwdMode)) { //$NON-NLS-1$
            if (project != null && project.getLocation() != null) {
                return normalizeDir(project.getLocation());
            }
        }
        if (resource != null && resource.getLocation() != null) {
            return normalizeDir(resource.getLocation());
        }
        if (project != null && project.getLocation() != null) {
            return normalizeDir(project.getLocation());
        }
        return workspaceLocation();
    }

    private static IPath workspaceLocation() {
        if (ResourcesPlugin.getWorkspace() != null
                && ResourcesPlugin.getWorkspace().getRoot() != null
                && ResourcesPlugin.getWorkspace().getRoot().getLocation() != null) {
            return normalizeDir(ResourcesPlugin.getWorkspace().getRoot().getLocation());
        }
        return null;
    }

    private static IPath normalizeDir(IPath path) {
        if (path == null) {
            return null;
        }
        if (path.toFile().isFile()) {
            return path.removeLastSegments(1);
        }
        return path;
    }

    private static ILauncherDelegate findLocalLauncher(ISelection selection) {
        LauncherDelegateManager manager = LauncherDelegateManager.getInstance();
        ILauncherDelegate[] delegates = manager.getApplicableLauncherDelegates(selection);
        if (delegates == null) {
            return null;
        }
        for (ILauncherDelegate delegate : delegates) {
            if (LOCAL_LAUNCHER_ID.equals(delegate.getId())) {
                return delegate;
            }
        }
        return null;
    }

    private static IProject resolveProject(IResource resource) {
        if (resource != null) {
            IProject project = resource.getProject();
            if (project != null) {
                return project;
            }
        }
        return resolveSingleProject();
    }

    private static IProject resolveSingleProject() {
        if (ResourcesPlugin.getWorkspace() == null || ResourcesPlugin.getWorkspace().getRoot() == null) {
            return null;
        }
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        if (projects == null || projects.length == 0) {
            return null;
        }
        if (projects.length == 1) {
            return projects[0];
        }
        for (IProject project : projects) {
            if (project != null && project.isOpen()) {
                return project;
            }
        }
        return null;
    }

    private static String buildTitle(IPath workingDir, String prefix) {
        String trimmedPrefix = prefix == null ? "" : prefix.trim(); //$NON-NLS-1$
        String base = workingDir == null ? null : workingDir.lastSegment();
        if (base == null || base.isBlank()) {
            return trimmedPrefix.isBlank() ? null : trimmedPrefix;
        }
        if (trimmedPrefix.isBlank()) {
            return base;
        }
        return trimmedPrefix + " " + base; //$NON-NLS-1$
    }

    private record TerminalLaunchContext(ISelection selection, IPath workingDir) {
    }
}
