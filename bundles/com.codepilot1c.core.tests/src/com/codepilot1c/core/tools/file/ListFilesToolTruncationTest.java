/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codepilot1c.core.tools.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Regression: oversized workspace listings are capped and the drop count is
 * surfaced through the {@code truncated_entries} marker.
 */
public class ListFilesToolTruncationTest {

    @Test
    public void oversizedListingIsTruncatedAndMarkerAppended() {
        // Build a synthetic list of entries large enough to overflow the budget.
        // Each entry ~410 chars -> ~100 entries give us ~41k chars.
        int entries = 200;
        String padding = repeat('x', 400);
        List<String> files = new ArrayList<>(entries);
        for (int i = 0; i < entries; i++) {
            files.add("file" + i + "_" + padding);
        }

        ToolResult out = ListFilesTool.renderListContents(
                "**Contents of:** `/demo`\n\n",
                files,
                null);

        assertTrue(out.isSuccess());
        String body = out.getContent();
        assertTrue("listing must stay within the char cap + small overhead",
                body.length() <= 40000 + 128);
        assertTrue("truncation marker must be present",
                body.contains("truncated_entries:"));
    }

    @Test
    public void smallListingIsNotTruncated() {
        List<String> files = new ArrayList<>();
        files.add("file1");
        files.add("file2");
        ToolResult out = ListFilesTool.renderListContents(
                "**Contents of:** `/demo`\n\n",
                files,
                null);

        assertTrue(out.isSuccess());
        String body = out.getContent();
        assertFalse("small listing must not advertise truncation",
                body.contains("truncated_entries:"));
        assertTrue(body.contains("file1"));
        assertTrue(body.contains("file2"));
    }

    @Test
    public void emptyListingReportsNoFilesWithoutMarker() {
        ToolResult out = ListFilesTool.renderListContents(
                "**Contents of:** `/demo`\n\n",
                new ArrayList<>(),
                "*.bsl");
        assertTrue(out.isSuccess());
        String body = out.getContent();
        assertTrue(body.contains("No files found"));
        assertFalse(body.contains("truncated_entries:"));
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        for (int i = 0; i < n; i++) {
            buf[i] = c;
        }
        return new String(buf);
    }
}
