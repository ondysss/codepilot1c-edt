/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ToolArgumentParser}.
 *
 * <p>Covers standard Gson parsing, the fallback simple parser for
 * malformed streaming JSON, and edge cases around type conversion.</p>
 */
public class ToolArgumentParserTest {

    private ToolArgumentParser parser;

    @Before
    public void setUp() {
        parser = new ToolArgumentParser();
    }

    // --- Null and empty inputs ---

    @Test
    public void nullInputReturnsEmptyMap() {
        Map<String, Object> result = parser.parseArguments(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void emptyStringReturnsEmptyMap() {
        Map<String, Object> result = parser.parseArguments(""); //$NON-NLS-1$
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void emptyObjectReturnsEmptyMap() {
        Map<String, Object> result = parser.parseArguments("{}"); //$NON-NLS-1$
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // --- Standard JSON parsing ---

    @Test
    public void parsesStringParameter() {
        Map<String, Object> result = parser.parseArguments(
                "{\"path\": \"/src/Main.java\"}"); //$NON-NLS-1$
        assertEquals("/src/Main.java", result.get("path")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void parsesIntegerParameter() {
        Map<String, Object> result = parser.parseArguments(
                "{\"start_line\": 42}"); //$NON-NLS-1$
        assertEquals(42, result.get("start_line")); //$NON-NLS-1$
    }

    @Test
    public void parsesLongParameter() {
        Map<String, Object> result = parser.parseArguments(
                "{\"offset\": 3000000000}"); //$NON-NLS-1$
        assertEquals(3000000000L, result.get("offset")); //$NON-NLS-1$
    }

    @Test
    public void parsesDoubleParameter() {
        Map<String, Object> result = parser.parseArguments(
                "{\"threshold\": 0.85}"); //$NON-NLS-1$
        assertEquals(0.85, (double) result.get("threshold"), 0.001); //$NON-NLS-1$
    }

    @Test
    public void parsesBooleanParameter() {
        Map<String, Object> result = parser.parseArguments(
                "{\"recursive\": true}"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, result.get("recursive")); //$NON-NLS-1$
    }

    @Test
    public void parsesNullParameter() {
        Map<String, Object> result = parser.parseArguments(
                "{\"optional\": null}"); //$NON-NLS-1$
        assertTrue(result.containsKey("optional")); //$NON-NLS-1$
        assertNull(result.get("optional")); //$NON-NLS-1$
    }

    @Test
    public void parsesMultipleParameters() {
        Map<String, Object> result = parser.parseArguments(
                "{\"path\": \"test.bsl\", \"start_line\": 10, \"end_line\": 20}"); //$NON-NLS-1$
        assertEquals("test.bsl", result.get("path")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(10, result.get("start_line")); //$NON-NLS-1$
        assertEquals(20, result.get("end_line")); //$NON-NLS-1$
    }

    // --- Nested objects and arrays ---

    @Test
    @SuppressWarnings("unchecked")
    public void parsesNestedObject() {
        Map<String, Object> result = parser.parseArguments(
                "{\"config\": {\"key\": \"value\", \"count\": 5}}"); //$NON-NLS-1$
        Object config = result.get("config"); //$NON-NLS-1$
        assertTrue(config instanceof Map);
        Map<String, Object> configMap = (Map<String, Object>) config;
        assertEquals("value", configMap.get("key")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(5, configMap.get("count")); //$NON-NLS-1$
    }

    @Test
    @SuppressWarnings("unchecked")
    public void parsesArray() {
        Map<String, Object> result = parser.parseArguments(
                "{\"items\": [\"a\", \"b\", \"c\"]}"); //$NON-NLS-1$
        Object items = result.get("items"); //$NON-NLS-1$
        assertTrue(items instanceof List);
        List<Object> itemList = (List<Object>) items;
        assertEquals(3, itemList.size());
        assertEquals("a", itemList.get(0)); //$NON-NLS-1$
    }

    // --- Multiline strings (critical for SEARCH/REPLACE) ---

    @Test
    public void preservesNewlinesInStrings() {
        Map<String, Object> result = parser.parseArguments(
                "{\"content\": \"line1\\nline2\\nline3\"}"); //$NON-NLS-1$
        String content = (String) result.get("content"); //$NON-NLS-1$
        assertTrue(content.contains("\n")); //$NON-NLS-1$
        assertEquals("line1\nline2\nline3", content); //$NON-NLS-1$
    }

    @Test
    public void preservesTabsInStrings() {
        Map<String, Object> result = parser.parseArguments(
                "{\"content\": \"col1\\tcol2\"}"); //$NON-NLS-1$
        assertEquals("col1\tcol2", result.get("content")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- Fallback parser (malformed JSON from streaming) ---

    @Test
    public void fallbackParserHandlesTrailingComma() {
        // Gson will fail on trailing comma, fallback should handle it
        Map<String, Object> result = parser.parseArguments(
                "{\"path\": \"test.bsl\", \"recursive\": true,}"); //$NON-NLS-1$
        // Should still get the values (via fallback parser)
        assertNotNull(result);
        assertEquals("test.bsl", result.get("path")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void fallbackParserHandlesUnquotedValues() {
        // Fallback parser should handle numbers and booleans
        Map<String, Object> result = parser.parseArguments(
                "{\"count\": 42, \"flag\": true, \"nothing\": null}"); //$NON-NLS-1$
        assertNotNull(result);
        // These should be parsed correctly by Gson (valid JSON)
        assertEquals(42, result.get("count")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, result.get("flag")); //$NON-NLS-1$
    }

    // --- Non-object JSON ---

    @Test
    public void nonObjectJsonReturnsEmptyMap() {
        Map<String, Object> result = parser.parseArguments("[1, 2, 3]"); //$NON-NLS-1$
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void plainStringReturnsEmptyOrFallback() {
        Map<String, Object> result = parser.parseArguments("not json at all"); //$NON-NLS-1$
        assertNotNull(result);
        // Fallback parser should return empty for non-JSON
    }

    // --- Integer vs long boundary ---

    @Test
    public void integerMaxValueStaysInteger() {
        Map<String, Object> result = parser.parseArguments(
                "{\"val\": 2147483647}"); //$NON-NLS-1$
        Object val = result.get("val"); //$NON-NLS-1$
        assertTrue("Expected Integer for Integer.MAX_VALUE", val instanceof Integer); //$NON-NLS-1$
        assertEquals(Integer.MAX_VALUE, val);
    }

    @Test
    public void overflowIntBecomesLong() {
        Map<String, Object> result = parser.parseArguments(
                "{\"val\": 2147483648}"); //$NON-NLS-1$
        Object val = result.get("val"); //$NON-NLS-1$
        assertTrue("Expected Long for value > Integer.MAX_VALUE", val instanceof Long); //$NON-NLS-1$
        assertEquals(2147483648L, val);
    }
}
