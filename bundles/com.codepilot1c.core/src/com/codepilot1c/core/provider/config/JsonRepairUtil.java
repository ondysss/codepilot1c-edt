/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;

/**
 * Stateless utility for repairing truncated JSON from streaming LLM responses.
 *
 * <p>Qwen/DashScope (and some other providers) may truncate JSON arguments
 * mid-stream, produce trailing commas, or leave containers unclosed. This
 * utility attempts best-effort repair to salvage usable tool call arguments.</p>
 */
final class JsonRepairUtil {

    private JsonRepairUtil() {
    }

    /**
     * Attempts to repair truncated or malformed JSON.
     *
     * <p>Repair steps (in order):</p>
     * <ol>
     *   <li>Strip trailing commas</li>
     *   <li>Remove dangling colons (truncated key-value pairs)</li>
     *   <li>Close unclosed string literals</li>
     *   <li>Close unclosed containers ({@code {}}, {@code []})</li>
     * </ol>
     *
     * @param json the potentially truncated JSON string
     * @return the repaired JSON, or the original if already valid or unrepairable
     */
    static String repair(String json) {
        if (json == null || json.isBlank()) {
            return "{}"; //$NON-NLS-1$
        }

        String normalized = json.strip();
        if (isComplete(normalized)) {
            return normalized;
        }

        String repaired = normalized;

        // Step 1: Remove trailing commas
        repaired = repaired.replaceAll(",\\s*$", ""); //$NON-NLS-1$ //$NON-NLS-2$
        repaired = repaired.replaceAll(",\\s*([}\\]])", "$1"); //$NON-NLS-1$ //$NON-NLS-2$

        // Step 2: Handle dangling colon (truncated before value)
        if (repaired.matches(".*:\\s*$")) { //$NON-NLS-1$
            // Truncated right after colon — add null and close
            repaired = repaired + "null"; //$NON-NLS-1$
        }

        // Step 3: Close unclosed string literals
        if (hasOddQuoteCount(repaired)) {
            repaired = repaired + "\""; //$NON-NLS-1$
        }

        // Step 4: Close unclosed containers
        repaired = closeOpenContainers(repaired);

        if (isComplete(repaired)) {
            return repaired;
        }

        // Unrepairable — return original
        return normalized;
    }

    /**
     * Checks whether a JSON string is syntactically complete.
     */
    static boolean isComplete(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonParser.parseString(json);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Counts unclosed containers in a JSON string.
     * Useful for estimating how truncated the JSON is.
     *
     * @return number of unclosed {@code {}/{@code [}} containers
     */
    static int unclosedDepth(String json) {
        if (json == null) {
            return 0;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            }
        }
        return depth;
    }

    private static boolean hasOddQuoteCount(String value) {
        boolean escaped = false;
        int quoteCount = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                quoteCount++;
            }
        }
        return (quoteCount % 2) != 0;
    }

    private static String closeOpenContainers(String value) {
        boolean inString = false;
        boolean escaped = false;
        List<Character> stack = new ArrayList<>();

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{' || c == '[') {
                stack.add(Character.valueOf(c));
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            }
        }

        StringBuilder suffix = new StringBuilder();
        for (int i = stack.size() - 1; i >= 0; i--) {
            suffix.append(stack.get(i).charValue() == '{' ? '}' : ']');
        }
        return value + suffix;
    }
}
