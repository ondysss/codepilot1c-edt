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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Regression: glob results exceeding the 40k char budget are trimmed and a
 * {@code truncated_entries} marker reports the drop count.
 */
public class GlobToolTruncationTest {

    @Test
    public void oversizedGlobResultIsTruncatedAndMarkerAppended() {
        Path baseDir = Paths.get("/project").toAbsolutePath();

        // Each relative path adds ~410 chars -> a few hundred entries overflow the budget.
        int entries = 500;
        String padding = repeat('g', 400);
        List<Path> matches = new ArrayList<>(entries);
        for (int i = 0; i < entries; i++) {
            matches.add(baseDir.resolve("nested_" + i + "_" + padding + ".bsl"));
        }

        ToolResult out = new GlobTool().formatResult(matches, "**/*.bsl", baseDir, 500);

        assertTrue(out.isSuccess());
        String body = out.getContent();
        assertTrue("glob output must stay within the char cap + small overhead",
                body.length() <= 40000 + 128);
        assertTrue("glob output must carry the truncated_entries marker",
                body.contains("truncated_entries:"));
    }

    @Test
    public void smallGlobResultIsNotTruncated() {
        Path baseDir = Paths.get("/project").toAbsolutePath();
        List<Path> matches = new ArrayList<>();
        matches.add(baseDir.resolve("a.bsl"));
        matches.add(baseDir.resolve("b.bsl"));

        ToolResult out = new GlobTool().formatResult(matches, "*.bsl", baseDir, 100);

        assertTrue(out.isSuccess());
        String body = out.getContent();
        assertFalse("small glob result must not advertise truncation",
                body.contains("truncated_entries:"));
        assertTrue(body.contains("a.bsl"));
        assertTrue(body.contains("b.bsl"));
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        for (int i = 0; i < n; i++) {
            buf[i] = c;
        }
        return new String(buf);
    }
}
