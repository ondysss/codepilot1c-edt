/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.internal;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.remote.IRemoteWorkbenchBridge;
import com.codepilot1c.ui.theme.ThemeManager;
import com.codepilot1c.ui.tools.GetDiagnosticsTool;
import com.codepilot1c.ui.remote.RemoteWorkbenchBridge;

/**
 * The activator class controls the plug-in life cycle.
 */
public class VibeUiPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.codepilot1c.ui"; //$NON-NLS-1$


    private static VibeUiPlugin plugin;
    private ServiceRegistration<IRemoteWorkbenchBridge> remoteWorkbenchBridgeRegistration;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        log("1C Copilot UI plugin started"); //$NON-NLS-1$

        // Initialize UI services on UI thread. Use asyncExec to defer until workbench is ready.
        startUiServicesAsync();
    }

    /**
     * Starts the completion service asynchronously on the UI thread.
     * This ensures proper access to PlatformUI and workbench APIs.
     */
    private void startUiServicesAsync() {
        // Get the display - try workbench first, fallback to default
        Display display = null;
        try {
            IWorkbench workbench = PlatformUI.getWorkbench();
            if (workbench != null) {
                display = workbench.getDisplay();
            }
        } catch (IllegalStateException e) {
            // Workbench not yet created - will try later
        }

        if (display == null) {
            display = Display.getDefault();
        }

        final Display finalDisplay = display;
        if (finalDisplay != null && !finalDisplay.isDisposed()) {
            finalDisplay.asyncExec(() -> {
                try {
                    // Check if workbench is available now
                    if (PlatformUI.isWorkbenchRunning()) {
                        // Initialize theme manager
                        ThemeManager.getInstance().initialize(finalDisplay);
                        log("Theme manager initialized"); //$NON-NLS-1$

                        // Register UI tools
                        registerUiTools();
                        log("UI tools registered"); //$NON-NLS-1$

                        registerRemoteWorkbenchBridge();
                    }
                } catch (Exception e) {
                    log(e);
                }
            });
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        log("1C Copilot UI plugin stopping"); //$NON-NLS-1$

        // Dispose theme manager resources
        try {
            ThemeManager.getInstance().dispose();
        } catch (Exception e) {
            log(e);
        }
        if (remoteWorkbenchBridgeRegistration != null) {
            try {
                remoteWorkbenchBridgeRegistration.unregister();
            } catch (Exception e) {
                log(e);
            }
            remoteWorkbenchBridgeRegistration = null;
        }

        plugin = null;
        super.stop(context);
    }

    /**
     * Registers UI-specific tools that require workbench access.
     */
    private void registerUiTools() {
        ToolRegistry registry = ToolRegistry.getInstance();

        // Register get_diagnostics tool for auto-fix workflow
        registry.registerDynamicTool(new GetDiagnosticsTool());
    }

    private void registerRemoteWorkbenchBridge() {
        if (remoteWorkbenchBridgeRegistration != null) {
            return;
        }
        BundleContext context = getBundle() != null ? getBundle().getBundleContext() : null;
        if (context == null) {
            return;
        }
        remoteWorkbenchBridgeRegistration = context.registerService(
                IRemoteWorkbenchBridge.class,
                new RemoteWorkbenchBridge(),
                null);
        log("Remote workbench bridge registered"); //$NON-NLS-1$
    }

    /**
     * Returns the shared instance.
     *
     * @return the shared instance
     */
    public static VibeUiPlugin getDefault() {
        return plugin;
    }

    /**
     * Logs an error.
     *
     * @param e the exception to log
     */
    public static void log(Throwable e) {
        if (plugin != null) {
            plugin.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, e.getMessage(), e));
        }
        // Log error: "Error", e //$NON-NLS-1$
    }

    /**
     * Logs a message.
     *
     * @param message the message to log
     */
    public static void log(String message) {
        if (plugin != null) {
            plugin.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
        // Log: message
    }

    /**
     * Returns an image descriptor for the image file at the given plug-in relative path.
     *
     * @param path the path relative to the plugin
     * @return the image descriptor
     */
    public static ImageDescriptor getImageDescriptor(String path) {
        return imageDescriptorFromPlugin(PLUGIN_ID, path);
    }
}
