/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import org.junit.Test;

public class ToolResultTruncatorTest {

    private static final int CAP = 40000;
    private static final int HEAD = (CAP * 5) / 8; // 25000
    private static final int TAIL = (CAP * 2) / 8; // 10000

    @Test
    public void truncateText_nullReturnsNull() {
        assertNull(ToolResultTruncator.truncateText(null, CAP));
    }

    @Test
    public void truncateText_emptyStringUnchanged() {
        String empty = "";
        assertSame(empty, ToolResultTruncator.truncateText(empty, CAP));
    }

    @Test
    public void truncateText_underCapReturnedAsIs() {
        String small = "hello world";
        assertSame(small, ToolResultTruncator.truncateText(small, CAP));
    }

    @Test
    public void truncateText_exactlyAtCapReturnedAsIs() {
        String text = repeat('x', CAP);
        String out = ToolResultTruncator.truncateText(text, CAP);
        assertSame(text, out);
        assertEquals(CAP, out.length());
    }

    @Test
    public void truncateText_overCapKeepsHeadAndTail() {
        int len = CAP * 3;
        String text = repeat('x', len);
        String out = ToolResultTruncator.truncateText(text, CAP);
        assertTrue("should contain truncation marker", out.contains("[... truncated "));
        assertTrue("marker should mention char count",
                out.contains("[... truncated " + (len - HEAD - TAIL) + " chars ...]"));
        // Head and tail lengths are preserved exactly.
        assertEquals(HEAD, out.indexOf('\n'));
        // Total length is head + marker + tail; strictly less than original.
        assertTrue("truncated length must shrink", out.length() < len);
        // Tail is still made of original chars.
        assertTrue(out.endsWith(repeat('x', TAIL)));
    }

    @Test
    public void truncateText_ratiosParametricForSmallerCap() {
        int cap = 800;
        int head = (cap * 5) / 8; // 500
        int tail = (cap * 2) / 8; // 200
        String text = repeat('a', cap * 4);
        String out = ToolResultTruncator.truncateText(text, cap);
        assertTrue(out.contains("[... truncated "));
        // Head length is consistent with ratios.
        assertEquals(head, out.indexOf('\n'));
        assertTrue(out.endsWith(repeat('a', tail)));
    }

    @Test
    public void truncateJsonField_missingFieldUnchanged() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", "foo");
        JsonObject out = ToolResultTruncator.truncateJsonField(obj, "text", CAP);
        assertSame(obj, out);
        assertFalse(obj.has("truncated"));
        assertFalse(obj.has("original_length"));
        assertEquals("foo", obj.get("name").getAsString());
    }

    @Test
    public void truncateJsonField_underCapUnchanged() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", "foo");
        obj.addProperty("text", "short body");
        JsonObject out = ToolResultTruncator.truncateJsonField(obj, "text", CAP);
        assertSame(obj, out);
        assertEquals("short body", obj.get("text").getAsString());
        assertFalse(obj.has("truncated"));
    }

    @Test
    public void truncateJsonField_overCapReplacesFieldAndAddsMeta() {
        int original = CAP * 2;
        String big = repeat('y', original);
        JsonObject obj = new JsonObject();
        obj.addProperty("name", "Provesti");
        obj.addProperty("kind", "procedure");
        obj.addProperty("text", big);
        JsonObject out = ToolResultTruncator.truncateJsonField(obj, "text", CAP);

        assertSame(obj, out);
        // Other fields preserved.
        assertEquals("Provesti", obj.get("name").getAsString());
        assertEquals("procedure", obj.get("kind").getAsString());
        // Body shortened and marker present.
        String newText = obj.get("text").getAsString();
        assertTrue(newText.length() < big.length());
        assertTrue(newText.contains("[... truncated "));
        // Meta fields added.
        assertTrue(obj.get("truncated").getAsBoolean());
        assertEquals(original, obj.get("original_length").getAsInt());
        assertEquals(HEAD, obj.get("body_excerpt_head").getAsInt());
        assertEquals(TAIL, obj.get("body_excerpt_tail").getAsInt());
        // Head/tail chars are original.
        assertTrue(newText.startsWith(repeat('y', HEAD)));
        assertTrue(newText.endsWith(repeat('y', TAIL)));
    }

    @Test
    public void truncateJsonField_nullObjectReturnsNull() {
        assertNull(ToolResultTruncator.truncateJsonField(null, "text", CAP));
    }

    @Test
    public void truncateJsonField_nonStringFieldUnchanged() {
        JsonObject obj = new JsonObject();
        obj.addProperty("text", 42);
        JsonObject out = ToolResultTruncator.truncateJsonField(obj, "text", CAP);
        assertNotNull(out);
        assertEquals(42, obj.get("text").getAsInt());
        assertFalse(obj.has("truncated"));
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        for (int i = 0; i < n; i++) {
            buf[i] = c;
        }
        return new String(buf);
    }
}
