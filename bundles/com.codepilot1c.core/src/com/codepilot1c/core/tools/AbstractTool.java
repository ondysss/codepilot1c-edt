/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

/**
 * Base class for tool implementations.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>Automatic metadata from {@link ToolMeta} annotation</li>
 *   <li>{@link ToolParameters} wrapper for typed parameter access</li>
 *   <li>CompletableFuture wrapping with exception handling</li>
 *   <li>Logging via Eclipse ILog</li>
 * </ul>
 *
 * <p>Subclasses implement {@link #doExecute(ToolParameters)} instead of
 * {@link #execute(Map)}.</p>
 */
public abstract class AbstractTool implements ITool {

    private static final String PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    private static final ILog NOOP_LOG = new ILog() {
        @Override
        public void addLogListener(ILogListener listener) {
            // No-op in headless/plain JUnit mode.
        }

        @Override
        public Bundle getBundle() {
            return null;
        }

        @Override
        public void log(IStatus status) {
            // No-op in headless/plain JUnit mode.
        }

        @Override
        public void removeLogListener(ILogListener listener) {
            // No-op in headless/plain JUnit mode.
        }
    };

    protected final ILog log;

    private final String name;
    private final String category;
    private final String surfaceCategory;
    private final boolean mutating;
    private final boolean requiresValidationToken;
    private final Set<String> tags;

    protected AbstractTool() {
        this.log = createLogger();
        ToolMeta meta = getClass().getAnnotation(ToolMeta.class);
        if (meta != null) {
            this.name = meta.name();
            this.category = meta.category();
            this.surfaceCategory = meta.surfaceCategory();
            this.mutating = meta.mutating();
            this.requiresValidationToken = meta.requiresValidationToken();
            this.tags = Set.of(meta.tags());
        } else {
            // Fallback for subclasses without annotation
            this.name = inferName();
            this.category = "general"; //$NON-NLS-1$
            this.surfaceCategory = ""; //$NON-NLS-1$
            this.mutating = false;
            this.requiresValidationToken = false;
            this.tags = Collections.emptySet();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCategory() {
        return category;
    }

    @Override
    public String getSurfaceCategory() {
        return surfaceCategory;
    }

    @Override
    public boolean isMutating() {
        return mutating;
    }

    @Override
    public boolean requiresValidationToken() {
        return requiresValidationToken;
    }

    @Override
    public Set<String> getTags() {
        return tags;
    }

    @Override
    public boolean requiresConfirmation() {
        return mutating;
    }

    @Override
    public boolean isDestructive() {
        return mutating;
    }

    @Override
    public final CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        try {
            ToolParameters params = new ToolParameters(parameters);
            return doExecute(params);
        } catch (ToolParameters.ToolParameterException e) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(String.format("Parameter error in %s: %s", name, e.getMessage()))); //$NON-NLS-1$
        } catch (Exception e) {
            log.log(new Status(IStatus.ERROR, "com.codepilot1c.core", //$NON-NLS-1$
                    String.format("Tool %s failed: %s", name, e.getMessage()), e)); //$NON-NLS-1$
            return CompletableFuture.completedFuture(
                    ToolResult.failure(String.format("Internal error in %s: %s", name, e.getMessage()))); //$NON-NLS-1$
        }
    }

    /**
     * Executes the tool with typed parameters.
     *
     * @param params typed parameter wrapper
     * @return a future containing the tool result
     */
    protected abstract CompletableFuture<ToolResult> doExecute(ToolParameters params);

    private ILog createLogger() {
        try {
            Bundle bundle = Platform.getBundle(PLUGIN_ID);
            if (bundle != null) {
                return Platform.getLog(bundle);
            }
        } catch (RuntimeException ignored) {
            // Fall through to a no-op logger in plain JUnit/headless mode.
        }
        return NOOP_LOG;
    }

    private String inferName() {
        String className = getClass().getSimpleName();
        // Convert "ReadFileTool" → "read_file"
        if (className.endsWith("Tool")) { //$NON-NLS-1$
            className = className.substring(0, className.length() - 4);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < className.length(); i++) {
            char c = className.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
