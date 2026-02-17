/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.codepilot1c.core.http.DefaultHttpClientFactory;
import com.codepilot1c.core.http.HttpClientFactory;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.McpHostManager;
import com.codepilot1c.core.mcp.McpServerManager;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.state.VibeStateService;

/**
 * The activator class controls the plug-in life cycle.
 */
public class VibeCorePlugin extends Plugin {

    public static final String PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    private static VibeCorePlugin plugin;
    private static ILog logger;
    private static final long EDT_SERVICE_WAIT_STEP_MS = 1000L;
    private static final long EDT_SERVICE_WAIT_TOTAL_MS = 30000L;
    private HttpClientFactory httpClientFactory;
    private ServiceTracker<IConfigurationProvider, IConfigurationProvider> configurationProviderTracker;
    private ServiceTracker<IBmModelManager, IBmModelManager> bmModelManagerTracker;
    private ServiceTracker<IDtProjectManager, IDtProjectManager> dtProjectManagerTracker;
    private ServiceTracker<IV8ProjectManager, IV8ProjectManager> v8ProjectManagerTracker;
    private ServiceTracker<IDerivedDataManagerProvider, IDerivedDataManagerProvider> derivedDataManagerProviderTracker;
    private ServiceTracker<BmAwareResourceSetProvider, BmAwareResourceSetProvider> resourceSetProviderTracker;
    private ServiceTracker<ITopObjectFqnGenerator, ITopObjectFqnGenerator> topObjectFqnGeneratorTracker;
    private ServiceTracker<IMarkerManager, IMarkerManager> markerManagerTracker;
    private ServiceTracker<ICheckRepository, ICheckRepository> checkRepositoryTracker;
    private ServiceTracker<IApplicationManager, IApplicationManager> applicationManagerTracker;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        logger = Platform.getLog(getClass());

        // Initialize HTTP client factory
        httpClientFactory = new DefaultHttpClientFactory();

        // Configure VibeLogger for development (DEBUG level + file logging)
        VibeLogger vibeLogger = VibeLogger.getInstance();
        vibeLogger.setMinLevel(VibeLogger.Level.DEBUG);
        vibeLogger.setLogToFile(true);
        vibeLogger.setLogToEclipse(true);

        logInfo("1C Copilot Core plugin started"); //$NON-NLS-1$
        vibeLogger.info("Core", "VibeLogger initialized. Log file: %s", vibeLogger.getLogFilePath()); //$NON-NLS-1$ //$NON-NLS-2$

        // Initialize LLM providers and set initial state.
        // If no providers are configured, plugin still starts but shows NOT_CONFIGURED.
        try {
            LlmProviderRegistry registry = LlmProviderRegistry.getInstance();
            registry.initialize();
            var active = registry.getActiveProvider();
            if (active != null && active.isConfigured()) {
                VibeStateService.getInstance().setIdle();
            } else {
                VibeStateService.getInstance().setNotConfigured(
                        "No LLM providers configured. Configure one in Preferences."); //$NON-NLS-1$
            }
        } catch (Exception e) {
            VibeStateService.getInstance().setError(e.getMessage());
            vibeLogger.error("Core", "Failed to initialize LLM providers", e); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Start enabled MCP servers asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                McpServerManager.getInstance().startEnabledServers();
            } catch (Exception e) {
                vibeLogger.error("Core", "Failed to start MCP servers", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });

        // Start inbound MCP host (for external clients) if enabled.
        CompletableFuture.runAsync(() -> {
            try {
                McpHostManager.getInstance().startIfEnabled();
            } catch (Exception e) {
                vibeLogger.error("Core", "Failed to start MCP host", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });

        // EDT runtime services for AST/BM integrations.
        configurationProviderTracker = new ServiceTracker<>(context, IConfigurationProvider.class, null);
        configurationProviderTracker.open();
        bmModelManagerTracker = new ServiceTracker<>(context, IBmModelManager.class, null);
        bmModelManagerTracker.open();
        dtProjectManagerTracker = new ServiceTracker<>(context, IDtProjectManager.class, null);
        dtProjectManagerTracker.open();
        v8ProjectManagerTracker = new ServiceTracker<>(context, IV8ProjectManager.class, null);
        v8ProjectManagerTracker.open();
        derivedDataManagerProviderTracker = new ServiceTracker<>(context, IDerivedDataManagerProvider.class, null);
        derivedDataManagerProviderTracker.open();
        resourceSetProviderTracker = new ServiceTracker<>(context, BmAwareResourceSetProvider.class, null);
        resourceSetProviderTracker.open();
        topObjectFqnGeneratorTracker = new ServiceTracker<>(context, ITopObjectFqnGenerator.class, null);
        topObjectFqnGeneratorTracker.open();
        markerManagerTracker = new ServiceTracker<>(context, IMarkerManager.class, null);
        markerManagerTracker.open();
        checkRepositoryTracker = new ServiceTracker<>(context, ICheckRepository.class, null);
        checkRepositoryTracker.open();
        applicationManagerTracker = new ServiceTracker<>(context, IApplicationManager.class, null);
        applicationManagerTracker.open();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        logInfo("1C Copilot Core plugin stopping"); //$NON-NLS-1$

        // Stop all MCP servers
        try {
            McpServerManager.getInstance().stopAllServers();
        } catch (Exception e) {
            logWarn("Error stopping MCP servers", e); //$NON-NLS-1$
        }
        try {
            McpHostManager.getInstance().stopAll();
        } catch (Exception e) {
            logWarn("Error stopping MCP host", e); //$NON-NLS-1$
        }

        // Dispose HTTP client factory
        if (httpClientFactory != null) {
            try {
                httpClientFactory.dispose();
            } catch (Exception e) {
                logWarn("Error disposing HTTP client factory", e); //$NON-NLS-1$
            }
            httpClientFactory = null;
        }

        try {
            LlmProviderRegistry.getInstance().dispose();
        } catch (Exception e) {
            logWarn("Error disposing LLM provider registry", e); //$NON-NLS-1$
        }

        closeTracker(configurationProviderTracker);
        configurationProviderTracker = null;
        closeTracker(bmModelManagerTracker);
        bmModelManagerTracker = null;
        closeTracker(dtProjectManagerTracker);
        dtProjectManagerTracker = null;
        closeTracker(v8ProjectManagerTracker);
        v8ProjectManagerTracker = null;
        closeTracker(derivedDataManagerProviderTracker);
        derivedDataManagerProviderTracker = null;
        closeTracker(resourceSetProviderTracker);
        resourceSetProviderTracker = null;
        closeTracker(topObjectFqnGeneratorTracker);
        topObjectFqnGeneratorTracker = null;
        closeTracker(markerManagerTracker);
        markerManagerTracker = null;
        closeTracker(checkRepositoryTracker);
        checkRepositoryTracker = null;
        closeTracker(applicationManagerTracker);
        applicationManagerTracker = null;

        plugin = null;
        super.stop(context);
    }

    private void closeTracker(ServiceTracker<?, ?> tracker) {
        if (tracker != null) {
            try {
                tracker.close();
            } catch (Exception e) {
                logWarn("Error closing service tracker", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Returns the shared instance.
     *
     * @return the shared instance
     */
    public static VibeCorePlugin getDefault() {
        return plugin;
    }

    /**
     * Returns the HTTP client factory.
     *
     * @return the HTTP client factory
     */
    public HttpClientFactory getHttpClientFactory() {
        return httpClientFactory;
    }

    public IConfigurationProvider getConfigurationProvider() {
        return getTrackedService(configurationProviderTracker, "IConfigurationProvider"); //$NON-NLS-1$
    }

    public IBmModelManager getBmModelManager() {
        return getTrackedService(bmModelManagerTracker, "IBmModelManager"); //$NON-NLS-1$
    }

    public IDtProjectManager getDtProjectManager() {
        return getTrackedService(dtProjectManagerTracker, "IDtProjectManager"); //$NON-NLS-1$
    }

    public IV8ProjectManager getV8ProjectManager() {
        return getTrackedService(v8ProjectManagerTracker, "IV8ProjectManager"); //$NON-NLS-1$
    }

    public IDerivedDataManagerProvider getDerivedDataManagerProvider() {
        return getTrackedService(derivedDataManagerProviderTracker, "IDerivedDataManagerProvider"); //$NON-NLS-1$
    }

    public BmAwareResourceSetProvider getResourceSetProvider() {
        return getTrackedService(resourceSetProviderTracker, "BmAwareResourceSetProvider"); //$NON-NLS-1$
    }

    public ITopObjectFqnGenerator getTopObjectFqnGenerator() {
        return getTrackedService(topObjectFqnGeneratorTracker, "ITopObjectFqnGenerator"); //$NON-NLS-1$
    }

    public IMarkerManager getMarkerManager() {
        return getTrackedService(markerManagerTracker, "IMarkerManager"); //$NON-NLS-1$
    }

    public ICheckRepository getCheckRepository() {
        return getTrackedService(checkRepositoryTracker, "ICheckRepository"); //$NON-NLS-1$
    }

    public IApplicationManager getApplicationManager() {
        return getTrackedService(applicationManagerTracker, "IApplicationManager"); //$NON-NLS-1$
    }

    private <T> T getTrackedService(ServiceTracker<T, T> tracker, String serviceName) {
        if (tracker == null) {
            return null;
        }
        T service = tracker.getService();
        if (service != null) {
            return service;
        }
        long waitedMs = 0L;
        while (waitedMs < EDT_SERVICE_WAIT_TOTAL_MS) {
            long waitSliceMs = Math.min(EDT_SERVICE_WAIT_STEP_MS, EDT_SERVICE_WAIT_TOTAL_MS - waitedMs);
            try {
                service = tracker.waitForService(waitSliceMs);
                if (service != null) {
                    return service;
                }
                service = tracker.getService();
                if (service != null) {
                    return service;
                }
                waitedMs += waitSliceMs;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logWarn("Interrupted while waiting for EDT service: " + serviceName, e); //$NON-NLS-1$
                return null;
            }
        }
        logWarn("EDT service not available after wait (" + EDT_SERVICE_WAIT_TOTAL_MS + " ms): " + serviceName); //$NON-NLS-1$ //$NON-NLS-2$
        return tracker.getService();
    }

    /**
     * Logs an info message.
     *
     * @param message the message
     */
    public static void logInfo(String message) {
        if (logger != null) {
            logger.log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
    }

    /**
     * Logs an error.
     *
     * @param message the message
     * @param e the exception
     */
    public static void logError(String message, Throwable e) {
        if (logger != null) {
            logger.log(new Status(IStatus.ERROR, PLUGIN_ID, message, e));
        }
    }

    /**
     * Logs an error.
     *
     * @param e the exception
     */
    public static void logError(Throwable e) {
        logError(e.getMessage(), e);
    }

    /**
     * Logs a warning message.
     *
     * @param message the message
     */
    public static void logWarn(String message) {
        if (logger != null) {
            logger.log(new Status(IStatus.WARNING, PLUGIN_ID, message));
        }
    }

    /**
     * Logs a warning message with exception.
     *
     * @param message the message
     * @param e the exception
     */
    public static void logWarn(String message, Throwable e) {
        if (logger != null) {
            logger.log(new Status(IStatus.WARNING, PLUGIN_ID, message, e));
        }
    }
}
