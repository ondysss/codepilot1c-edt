package com.codepilot1c.core.edt.runtime;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
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

    /**
     * Returns the standalone-server service if available, or {@code null} if the service is
     * not registered. Standalone-server binding is an optional fallback path, so the service
     * may legitimately be unavailable (e.g. during tests or when the EDT component is missing);
     * callers must handle a {@code null} return value.
     *
     * <p><b>Warning:</b> this accessor delegates to the waiting lookup in
     * {@link VibeCorePlugin#getStandaloneServerService()}, which blocks the calling thread for
     * up to {@code EDT_SERVICE_WAIT_TOTAL_MS} milliseconds if the service has not been
     * registered yet. For latency-sensitive code paths that can tolerate a missing service,
     * prefer {@link #peekStandaloneServerService()} instead.
     */
    public IStandaloneServerService getStandaloneServerServiceOrNull() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin == null) {
            return null;
        }
        return plugin.getStandaloneServerService();
    }

    /**
     * Non-blocking variant of {@link #getStandaloneServerServiceOrNull()}: returns the
     * currently-registered standalone-server service, or {@code null} immediately if the
     * service is not available. Safe to call from code paths (such as
     * {@code EdtRuntimeService.resolveDefaultInfobase}) where a missing standalone binding is
     * an expected fallback outcome and a 30-second wait would stall the agent tool dispatcher.
     */
    public IStandaloneServerService peekStandaloneServerService() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin == null) {
            return null;
        }
        return plugin.peekStandaloneServerService();
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
