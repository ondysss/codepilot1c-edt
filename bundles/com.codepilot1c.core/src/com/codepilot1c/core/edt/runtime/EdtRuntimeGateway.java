package com.codepilot1c.core.edt.runtime;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com.codepilot1c.core.internal.VibeCorePlugin;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

public class EdtRuntimeGateway {

    public IProject resolveProject(String projectName) {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        if (workspace == null || projectName == null || projectName.isBlank()) {
            return null;
        }
        return workspace.getRoot().getProject(projectName);
    }

    public IInfobaseAssociationManager getInfobaseAssociationManager() {
        VibeCorePlugin plugin = requirePlugin();
        IInfobaseAssociationManager service = plugin.getInfobaseAssociationManager();
        if (service == null) {
            throw serviceUnavailable("IInfobaseAssociationManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IInfobaseAccessManager getInfobaseAccessManager() {
        VibeCorePlugin plugin = requirePlugin();
        IInfobaseAccessManager service = plugin.getInfobaseAccessManager();
        if (service == null) {
            throw serviceUnavailable("IInfobaseAccessManager"); //$NON-NLS-1$
        }
        return service;
    }

    public Object getInfobaseSynchronizationManager() {
        VibeCorePlugin plugin = requirePlugin();
        BundleContext context = plugin.getBundle() == null ? null : plugin.getBundle().getBundleContext();
        if (context == null) {
            throw serviceUnavailable("BundleContext"); //$NON-NLS-1$
        }
        String className = "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager"; //$NON-NLS-1$
        ServiceReference<?> ref = context.getServiceReference(className);
        if (ref == null) {
            throw serviceUnavailable("IInfobaseSynchronizationManager"); //$NON-NLS-1$
        }
        Object service = context.getService(ref);
        if (service == null) {
            throw serviceUnavailable("IInfobaseSynchronizationManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IRuntimeComponentManager getRuntimeComponentManager() {
        VibeCorePlugin plugin = requirePlugin();
        IRuntimeComponentManager service = plugin.getRuntimeComponentManager();
        if (service == null) {
            throw serviceUnavailable("IRuntimeComponentManager"); //$NON-NLS-1$
        }
        return service;
    }

    private VibeCorePlugin requirePlugin() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin == null) {
            throw serviceUnavailable("VibeCorePlugin"); //$NON-NLS-1$
        }
        return plugin;
    }

    private IllegalStateException serviceUnavailable(String name) {
        return new IllegalStateException("EDT service not available: " + name); //$NON-NLS-1$
    }
}
