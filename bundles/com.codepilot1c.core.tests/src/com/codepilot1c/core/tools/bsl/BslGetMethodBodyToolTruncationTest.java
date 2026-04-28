/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.bsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codepilot1c.core.edt.lang.BslMethodBodyRequest;
import com.codepilot1c.core.edt.lang.BslMethodBodyResult;
import com.codepilot1c.core.edt.lang.BslSemanticService;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.Test;

/**
 * Regression: the {@code bsl_get_method_body} tool must cap oversized method
 * body text and still produce valid JSON whose structural fields are preserved.
 */
public class BslGetMethodBodyToolTruncationTest {

    private static final int CAP = 40000;

    @Test
    public void oversizedBodyIsTruncatedAndJsonStillParses() {
        String huge = repeat('b', CAP * 2);
        BslMethodBodyResult result = new BslMethodBodyResult(
                "DemoConfiguration",
                "CommonModules/Orders/Module.bsl",
                "Provesti",
                "procedure",
                10,
                2020,
                huge);

        FakeService service = new FakeService(result);
        ToolResult toolResult = new BslGetMethodBodyTool(service).execute(Map.of(
                "projectName", "DemoConfiguration",
                "filePath", "CommonModules/Orders/Module.bsl",
                "name", "Provesti"
        )).join();

        assertTrue(toolResult.isSuccess());

        // Must still parse cleanly after truncation.
        JsonObject json = JsonParser.parseString(toolResult.getContent()).getAsJsonObject();

        // Structural fields preserved.
        assertEquals("DemoConfiguration", json.get("projectName").getAsString());
        assertEquals("CommonModules/Orders/Module.bsl", json.get("filePath").getAsString());
        assertEquals("Provesti", json.get("name").getAsString());
        assertEquals("procedure", json.get("kind").getAsString());
        assertEquals(10, json.get("startLine").getAsInt());
        assertEquals(2020, json.get("endLine").getAsInt());

        // Body was replaced with a shorter excerpt containing the marker.
        String body = json.get("text").getAsString();
        assertTrue("body must be shorter than original", body.length() < huge.length());
        assertTrue("body must contain truncation marker", body.contains("[... truncated "));

        // Meta fields describing the drop are present.
        assertTrue(json.get("truncated").getAsBoolean());
        assertEquals(huge.length(), json.get("original_length").getAsInt());
        assertEquals((CAP * 5) / 8, json.get("body_excerpt_head").getAsInt());
        assertEquals((CAP * 2) / 8, json.get("body_excerpt_tail").getAsInt());
    }

    @Test
    public void smallBodyIsNotTruncated() {
        String small = "Procedure Provesti() EndProcedure";
        BslMethodBodyResult result = new BslMethodBodyResult(
                "DemoConfiguration",
                "CommonModules/Orders/Module.bsl",
                "Provesti",
                "procedure",
                10,
                12,
                small);

        FakeService service = new FakeService(result);
        ToolResult toolResult = new BslGetMethodBodyTool(service).execute(Map.of(
                "projectName", "DemoConfiguration",
                "filePath", "CommonModules/Orders/Module.bsl",
                "name", "Provesti"
        )).join();

        assertTrue(toolResult.isSuccess());
        JsonObject json = JsonParser.parseString(toolResult.getContent()).getAsJsonObject();
        assertEquals(small, json.get("text").getAsString());
        assertFalse("small bodies must not be flagged as truncated", json.has("truncated"));
        assertFalse(json.has("original_length"));
        assertFalse(json.has("body_excerpt_head"));
        assertFalse(json.has("body_excerpt_tail"));
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        for (int i = 0; i < n; i++) {
            buf[i] = c;
        }
        return new String(buf);
    }

    private static final class FakeService extends BslSemanticService {
        private final BslMethodBodyResult result;

        FakeService(BslMethodBodyResult result) {
            this.result = result;
        }

        @Override
        public BslMethodBodyResult getMethodBody(BslMethodBodyRequest request) {
            return result;
        }
    }
}
