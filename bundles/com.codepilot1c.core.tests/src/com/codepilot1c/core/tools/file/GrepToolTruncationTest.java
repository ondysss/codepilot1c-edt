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
 * Regression: when the rendered grep output exceeds the 40k char budget,
 * overflow matches are dropped and replaced with a {@code truncated_matches}
 * marker line.
 */
public class GrepToolTruncationTest {

    @Test
    public void oversizedMatchSetIsTruncatedAndMarkerAppended() {
        // Build enough matches so that their rendered size comfortably exceeds 40000 chars.
        // Each match contributes ~2_100 chars (big context block), so 40 matches is ~84_000.
        int matchCount = 40;
        String bigContext = repeat('c', 2000);
        List<GrepTool.SearchMatch> matches = new ArrayList<>(matchCount);
        for (int i = 0; i < matchCount; i++) {
            matches.add(new GrepTool.SearchMatch(
                    "/project/module" + i + ".bsl",
                    10 + i,
                    "line",
                    bigContext));
        }

        ToolResult out = new GrepTool().formatResults("pattern", matches);

        assertTrue(out.isSuccess());
        String body = out.getContent();
        assertTrue("output must stay within the char cap + small marker overhead",
                body.length() <= 40000 + 128);
        assertTrue("output must carry the truncated_matches marker",
                body.contains("truncated_matches:"));
    }

    @Test
    public void smallMatchSetIsNotTruncated() {
        List<GrepTool.SearchMatch> matches = new ArrayList<>();
        matches.add(new GrepTool.SearchMatch("/a.bsl", 1, "line", "context"));

        ToolResult out = new GrepTool().formatResults("x", matches);

        assertTrue(out.isSuccess());
        assertFalse("small result must not advertise truncation",
                out.getContent().contains("truncated_matches:"));
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        for (int i = 0; i < n; i++) {
            buf[i] = c;
        }
        return new String(buf);
    }
}
