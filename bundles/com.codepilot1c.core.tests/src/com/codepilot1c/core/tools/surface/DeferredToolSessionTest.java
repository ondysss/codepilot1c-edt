/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the deferred tool surface gate, with focus on the MCP/dynamic
 * visibility regression fix (Plan 2.1).
 *
 * <p>Contract exercised by {@link DeferredToolSession#shouldIncludeTool}:</p>
 * <ul>
 *   <li>Core built-ins are always part of the deferred base set.</li>
 *   <li>{@code discover_tools} is always part of the deferred base set.</li>
 *   <li>{@link ToolCategory#DYNAMIC} tools (MCP/UI contributions) are part of
 *       the deferred base set so agent capability discovery is not hidden.</li>
 *   <li>Non-core, non-dynamic categories are absent until explicitly
 *       discovered via {@code discover_tools}.</li>
 * </ul>
 */
public class DeferredToolSessionTest {

    @Test
    public void inactiveSessionIncludesEverything() {
        DeferredToolSession session = new DeferredToolSession(false);

        assertTrue(session.shouldIncludeTool("read_file", ToolCategory.FILES_READ_SEARCH)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("mcp__foo__bar", ToolCategory.DYNAMIC)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("qa_inspect", ToolCategory.QA)); //$NON-NLS-1$
    }

    @Test
    public void dynamicToolsPassGateInDeferredBaseSet() {
        DeferredToolSession session = new DeferredToolSession(true);

        // MCP / UI contributions flow through the DYNAMIC category and must
        // remain visible when deferred loading is active.
        assertTrue(session.shouldIncludeTool("mcp__foo__bar", ToolCategory.DYNAMIC)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("some_ui_action", ToolCategory.DYNAMIC)); //$NON-NLS-1$
    }

    @Test
    public void coreBuiltinsPassGateInDeferredBaseSet() {
        DeferredToolSession session = new DeferredToolSession(true);

        // Core file tools live in the core categories.
        assertTrue(session.shouldIncludeTool("read_file", ToolCategory.FILES_READ_SEARCH)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("grep", ToolCategory.FILES_READ_SEARCH)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("edit_file", ToolCategory.FILES_WRITE_EDIT)); //$NON-NLS-1$

        // Always-core tools keyed by name.
        assertTrue(session.shouldIncludeTool("task", ToolCategory.EDT_SEMANTIC_READ)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("skill", ToolCategory.EDT_SEMANTIC_READ)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("git_inspect", ToolCategory.WORKSPACE_GIT_IMPORT)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("git_mutate", ToolCategory.WORKSPACE_GIT_IMPORT)); //$NON-NLS-1$
    }

    @Test
    public void discoverToolsPassesGateInDeferredBaseSet() {
        DeferredToolSession session = new DeferredToolSession(true);

        assertTrue(session.shouldIncludeTool("discover_tools", ToolCategory.EDT_SEMANTIC_READ)); //$NON-NLS-1$
    }

    @Test
    public void otherCategoriesAreHiddenUntilDiscoverToolsIsCalled() {
        DeferredToolSession session = new DeferredToolSession(true);

        // A non-core, non-dynamic category stays hidden until discovered.
        assertFalse(session.shouldIncludeTool("qa_inspect", ToolCategory.QA)); //$NON-NLS-1$
        assertFalse(session.shouldIncludeTool("ensure_module_artifact", //$NON-NLS-1$
                ToolCategory.METADATA_MUTATION));
        assertFalse(session.shouldIncludeTool("bsl_module_context", //$NON-NLS-1$
                ToolCategory.EDT_SEMANTIC_READ));

        // After discover_tools surfaces a category, its tools become visible.
        session.markDiscovered(ToolCategory.QA);
        assertTrue(session.shouldIncludeTool("qa_inspect", ToolCategory.QA)); //$NON-NLS-1$

        // Other categories remain hidden — discovery is category-scoped.
        assertFalse(session.shouldIncludeTool("ensure_module_artifact", //$NON-NLS-1$
                ToolCategory.METADATA_MUTATION));
    }

    @Test
    public void resetClearsDiscoveredCategoriesButKeepsDynamicAndCore() {
        DeferredToolSession session = new DeferredToolSession(true);
        session.markDiscovered(ToolCategory.QA);
        assertTrue(session.shouldIncludeTool("qa_inspect", ToolCategory.QA)); //$NON-NLS-1$

        session.reset();

        // Discovered-only categories go back to hidden after reset.
        assertFalse(session.shouldIncludeTool("qa_inspect", ToolCategory.QA)); //$NON-NLS-1$
        // Dynamic and core tools remain in the base set.
        assertTrue(session.shouldIncludeTool("mcp__foo__bar", ToolCategory.DYNAMIC)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("read_file", ToolCategory.FILES_READ_SEARCH)); //$NON-NLS-1$
        assertTrue(session.shouldIncludeTool("discover_tools", ToolCategory.EDT_SEMANTIC_READ)); //$NON-NLS-1$
    }
}
