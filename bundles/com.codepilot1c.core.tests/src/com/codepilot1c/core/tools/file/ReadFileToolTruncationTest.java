/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression: oversized file text is capped before leaving the tool.
 */
public class ReadFileToolTruncationTest {

    @Test
    public void oversizedContentIsCapped() {
        int cap = ReadFileTool.maxOutputChars();
        String oversized = repeat('z', cap * 2);

        String capped = ReadFileTool.capForOutput(oversized);

        assertTrue("capped length must fit budget", capped.length() <= cap + 64);
        assertTrue("capped content must carry the truncation marker",
                capped.contains("[... truncated "));
        // Head and tail must still come from the original text.
        assertTrue(capped.startsWith("z"));
        assertTrue(capped.endsWith("z"));
    }

    @Test
    public void smallContentIsReturnedAsIs() {
        String small = "line1\nline2\nline3";
        assertSame(small, ReadFileTool.capForOutput(small));
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        for (int i = 0; i < n; i++) {
            buf[i] = c;
        }
        return new String(buf);
    }
}
