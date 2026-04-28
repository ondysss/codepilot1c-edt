/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Regression tests for {@link ChatSystemPromptToolsSection}.
 *
 * <p>These pin the no-enumeration invariant: the tools section of the ChatView
 * system prompt MUST NOT list individual tool names or descriptions. The full
 * manifest is delivered to the model via the structured {@code tools} request
 * parameter; duplicating it in the prompt burned ~1200 tokens on every
 * request (codex review of the Phase 3 plan flagged this as the single
 * largest source of stable token overhead in ChatView).</p>
 *
 * <p>If a future change re-introduces per-tool enumeration in the system
 * prompt, the {@link #append_availableText_doesNotEnumerateTools} test fails
 * loudly so the regression cannot slip through review.</p>
 */
public class ChatSystemPromptToolsSectionTest {

    @Test
    public void append_nullBuilder_throws() {
        try {
            ChatSystemPromptToolsSection.append(null, true);
            fail("expected IllegalArgumentException for null builder"); //$NON-NLS-1$
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void append_unavailable_explainsAbsence() {
        StringBuilder sb = new StringBuilder();
        ChatSystemPromptToolsSection.append(sb, false);
        String out = sb.toString();

        assertTrue("must retain the section header", out.contains("# Инструменты")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must explain that tools are unavailable", //$NON-NLS-1$
            out.contains("недоступны")); //$NON-NLS-1$
        assertFalse("unavailable branch must not advertise discover_tools", //$NON-NLS-1$
            out.contains(ChatSystemPromptToolsSection.AVAILABLE_MARKER));
    }

    @Test
    public void append_available_pointsAtDiscoverTools() {
        StringBuilder sb = new StringBuilder();
        ChatSystemPromptToolsSection.append(sb, true);
        String out = sb.toString();

        assertTrue("must retain the section header", out.contains("# Инструменты")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must direct the model to discover_tools for details", //$NON-NLS-1$
            out.contains(ChatSystemPromptToolsSection.AVAILABLE_MARKER));
    }

    /**
     * Token-budget regression: the full tools section must stay compact (it
     * used to exceed 1200 tokens by enumerating 57 tools and their
     * descriptions). This cap is deliberately generous — we only want to
     * catch someone re-adding a manifest dump, not to bikeshed phrasing.
     */
    @Test
    public void append_available_isCompact() {
        StringBuilder sb = new StringBuilder();
        ChatSystemPromptToolsSection.append(sb, true);
        int len = sb.length();

        assertTrue("tools section length=" + len + " chars; regressions usually blow past 2000", //$NON-NLS-1$ //$NON-NLS-2$
            len < 1000);
    }

    /**
     * Core invariant. If someone re-introduces a loop that prints tool names
     * or descriptions into the system prompt, one of these substrings will
     * leak through and this test will fail.
     */
    @Test
    public void append_availableText_doesNotEnumerateTools() {
        StringBuilder sb = new StringBuilder();
        ChatSystemPromptToolsSection.append(sb, true);
        String out = sb.toString();

        // Common tool-name fragments from ToolRegistry that would appear if a
        // manifest dump regressed back into the prompt.
        String[] forbidden = new String[] {
            "read_file", //$NON-NLS-1$
            "write_file", //$NON-NLS-1$
            "edit_file", //$NON-NLS-1$
            "grep_search", //$NON-NLS-1$
            "list_directory", //$NON-NLS-1$
            "run_shell", //$NON-NLS-1$
            "bsl_", //$NON-NLS-1$
            "edt_", //$NON-NLS-1$
            "mcp_", //$NON-NLS-1$
        };
        for (String fragment : forbidden) {
            assertFalse(
                "tools section must not enumerate individual tools; found fragment: " + fragment, //$NON-NLS-1$
                out.contains(fragment));
        }

        // Crude structural check: a markdown bullet list of tools usually
        // produces many "- " bullets. We allow a handful for prose but refuse
        // anything that looks like an enumerated list.
        int bullets = countOccurrences(out, "\n- "); //$NON-NLS-1$
        assertTrue("too many markdown bullets (" + bullets + "); looks like a tool manifest dump", //$NON-NLS-1$ //$NON-NLS-2$
            bullets <= 2);
    }

    @Test
    public void append_appendsRatherThanReplaces() {
        StringBuilder sb = new StringBuilder("PREFIX\n"); //$NON-NLS-1$
        ChatSystemPromptToolsSection.append(sb, true);
        String out = sb.toString();

        assertTrue("must preserve caller-provided prefix", out.startsWith("PREFIX\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must actually emit the section", out.contains("# Инструменты")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void append_idempotentFormat() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        ChatSystemPromptToolsSection.append(a, true);
        ChatSystemPromptToolsSection.append(b, true);
        assertEquals("output must be stable (prompt cache safety)", a.toString(), b.toString()); //$NON-NLS-1$
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
