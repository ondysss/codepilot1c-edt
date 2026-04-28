/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which tool categories have been discovered in the current agent session.
 *
 * <p>Thread-safe. Each agent run should create a new session or call {@link #reset()}.</p>
 */
public final class DeferredToolSession {

    private final Set<ToolCategory> discoveredCategories =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private volatile boolean deferredLoadingActive;

    /**
     * Creates a session with deferred loading disabled.
     */
    public DeferredToolSession() {
        this(false);
    }

    /**
     * Creates a session.
     *
     * @param deferredLoadingActive whether deferred loading is active
     */
    public DeferredToolSession(boolean deferredLoadingActive) {
        this.deferredLoadingActive = deferredLoadingActive;
    }

    /**
     * Returns whether deferred loading is active for this session.
     */
    public boolean isDeferredLoadingActive() {
        return deferredLoadingActive;
    }

    /**
     * Enables or disables deferred loading for this session.
     */
    public void setDeferredLoadingActive(boolean active) {
        this.deferredLoadingActive = active;
    }

    /**
     * Records a category as discovered (its tools should now be included).
     *
     * @param category the discovered category
     */
    public void markDiscovered(ToolCategory category) {
        if (category != null) {
            discoveredCategories.add(category);
        }
    }

    /**
     * Returns whether a category has been discovered.
     *
     * @param category the category to check
     * @return {@code true} if the category was discovered
     */
    public boolean isDiscovered(ToolCategory category) {
        return discoveredCategories.contains(category);
    }

    /**
     * Returns all discovered categories.
     *
     * @return unmodifiable set of discovered categories
     */
    public Set<ToolCategory> getDiscoveredCategories() {
        return Collections.unmodifiableSet(EnumSet.copyOf(
                discoveredCategories.isEmpty()
                        ? EnumSet.noneOf(ToolCategory.class)
                        : discoveredCategories));
    }

    /**
     * Returns whether a tool should be included in the current request.
     *
     * <p>A tool is included if:</p>
     * <ul>
     *   <li>Deferred loading is not active, OR</li>
     *   <li>The tool is a core tool, OR</li>
     *   <li>The tool's category is {@link ToolCategory#DYNAMIC} (MCP/UI
     *       contributions are part of the deferred base set so agent
     *       capability discovery is not hidden), OR</li>
     *   <li>The tool's category has been discovered</li>
     * </ul>
     *
     * @param toolName the tool name
     * @param category the tool's resolved category
     * @return {@code true} if the tool should be included
     */
    public boolean shouldIncludeTool(String toolName, ToolCategory category) {
        if (!deferredLoadingActive) {
            return true;
        }
        if (DeferredToolSet.isCoreToolByName(toolName, category)) {
            return true;
        }
        if (category == ToolCategory.DYNAMIC) {
            return true;
        }
        return discoveredCategories.contains(category);
    }

    /**
     * Resets the session, clearing all discovered categories.
     */
    public void reset() {
        discoveredCategories.clear();
    }
}
