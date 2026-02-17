package com.codepilot1c.core.edt.ast;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.codepilot1c.core.internal.VibeCorePlugin;

/**
 * EDT service gateway (strict EDT-only mode).
 */
public class EdtServiceGateway {

    public IProject resolveProject(String projectName) {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        if (workspace == null || projectName == null || projectName.isBlank()) {
            return null;
        }
        return workspace.getRoot().getProject(projectName);
    }

    public IFile resolveSourceFile(IProject project, String filePath) {
        if (project == null || filePath == null || filePath.isBlank()) {
            return null;
        }
        IPath relativePath = new Path("src").append(filePath); //$NON-NLS-1$
        IFile file = project.getFile(relativePath);
        if (file != null && file.exists()) {
            return file;
        }
        return null;
    }

    public IConfigurationProvider getConfigurationProvider() {
        VibeCorePlugin plugin = requirePlugin();
        IConfigurationProvider service = plugin.getConfigurationProvider();
        if (service == null) {
            throw serviceUnavailable("IConfigurationProvider"); //$NON-NLS-1$
        }
        return service;
    }

    public IBmModelManager getBmModelManager() {
        VibeCorePlugin plugin = requirePlugin();
        IBmModelManager service = plugin.getBmModelManager();
        if (service == null) {
            throw serviceUnavailable("IBmModelManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IDtProjectManager getDtProjectManager() {
        VibeCorePlugin plugin = requirePlugin();
        IDtProjectManager service = plugin.getDtProjectManager();
        if (service == null) {
            throw serviceUnavailable("IDtProjectManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IDerivedDataManagerProvider getDerivedDataManagerProvider() {
        VibeCorePlugin plugin = requirePlugin();
        IDerivedDataManagerProvider service = plugin.getDerivedDataManagerProvider();
        if (service == null) {
            throw serviceUnavailable("IDerivedDataManagerProvider"); //$NON-NLS-1$
        }
        return service;
    }

    public BmAwareResourceSetProvider getResourceSetProvider() {
        VibeCorePlugin plugin = requirePlugin();
        BmAwareResourceSetProvider service = plugin.getResourceSetProvider();
        if (service == null) {
            throw serviceUnavailable("BmAwareResourceSetProvider"); //$NON-NLS-1$
        }
        return service;
    }

    private VibeCorePlugin requirePlugin() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "VibeCorePlugin is not initialized. Wait until EDT finishes startup and retry.", true); //$NON-NLS-1$
        }
        return plugin;
    }

    private EdtAstException serviceUnavailable(String serviceName) {
        return new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                serviceName + " is unavailable in EDT runtime. Wait until EDT project services are initialized and retry.", true); //$NON-NLS-1$
    }
}
