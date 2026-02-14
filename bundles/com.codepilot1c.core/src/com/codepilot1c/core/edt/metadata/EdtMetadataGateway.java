package com.codepilot1c.core.edt.metadata;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.bm.integration.IBmPlatformGlobalEditingContext;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.codepilot1c.core.internal.VibeCorePlugin;

/**
 * Access to EDT runtime services.
 */
public class EdtMetadataGateway {

    public IProject resolveProject(String projectName) {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        if (workspace == null || projectName == null || projectName.isBlank()) {
            return null;
        }
        return workspace.getRoot().getProject(projectName);
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

    public IBmPlatformGlobalEditingContext getGlobalEditingContext() {
        IBmPlatformGlobalEditingContext service = getBmModelManager().getGlobalEditingContext();
        if (service == null) {
            throw serviceUnavailable("IBmPlatformGlobalEditingContext"); //$NON-NLS-1$
        }
        return service;
    }

    public void ensureValidationRuntimeAvailable() {
        // Validation requires access to project/configuration/readiness services.
        getConfigurationProvider();
        getDtProjectManager();
        getDerivedDataManagerProvider();
    }

    public void ensureMutationRuntimeAvailable() {
        // Mutation requires BM transaction services in addition to validation baseline.
        ensureValidationRuntimeAvailable();
        getBmModelManager();
        getGlobalEditingContext();
    }

    public boolean isEdtAvailable() {
        try {
            ensureMutationRuntimeAvailable();
            return true;
        } catch (MetadataOperationException e) {
            return false;
        }
    }

    private VibeCorePlugin requirePlugin() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "VibeCorePlugin is not initialized", false); //$NON-NLS-1$
        }
        return plugin;
    }

    private MetadataOperationException serviceUnavailable(String serviceName) {
        return new MetadataOperationException(
                MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                serviceName + " is unavailable in EDT runtime", false); //$NON-NLS-1$
    }
}
