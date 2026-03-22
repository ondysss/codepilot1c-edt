/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.ToolCall;

/**
 * Safety-net fallback parser for Qwen tool calls emitted as XML in text content.
 *
 * <p>This parser is invoked <b>only</b> when all of the following are true:</p>
 * <ol>
 *   <li>{@code ProviderCapabilities.isQwenNative() == true}</li>
 *   <li>The structured API returned <b>no</b> tool_calls</li>
 *   <li>The response content contains {@code <tool_call>} tags</li>
 * </ol>
 *
 * <p>In 95%+ of cases, Qwen uses the structured API correctly. But if the XML
 * priming in the system prompt triggers the model to output tool calls as XML
 * in its text response, this parser ensures they are still executed.</p>
 *
 * <p>Supports both Qwen-coder and Qwen-VL formats:</p>
 * <ul>
 *   <li>Format A (qwen-coder): {@code <function=NAME><parameter=KEY>value</parameter></function>}</li>
 *   <li>Format B (qwen-vl): {@code {"name": "...", "arguments": {...}}}</li>
 * </ul>
 */
final class QwenContentToolCallParser {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QwenContentToolCallParser.class);

    /** Pattern to extract &lt;tool_call&gt;...&lt;/tool_call&gt; blocks. */
    private static final Pattern TOOL_CALL_BLOCK_PATTERN =
            Pattern.compile("<tool_call>\\s*(.*?)\\s*</tool_call>", Pattern.DOTALL); //$NON-NLS-1$

    /** Pattern for qwen-coder format: &lt;function=NAME&gt; */
    private static final Pattern FUNCTION_NAME_PATTERN =
            Pattern.compile("<function=([^>]+)>", Pattern.DOTALL); //$NON-NLS-1$

    /** Pattern for qwen-coder parameter: &lt;parameter=KEY&gt;value&lt;/parameter&gt; */
    private static final Pattern PARAMETER_PATTERN =
            Pattern.compile("<parameter=([^>]+)>(.*?)</parameter>", Pattern.DOTALL); //$NON-NLS-1$

    /** Quick check pattern — avoids regex for the common case. */
    private static final String TOOL_CALL_MARKER = "<tool_call>"; //$NON-NLS-1$

    private QwenContentToolCallParser() {
    }

    /**
     * Extracts tool calls from XML content.
     *
     * @param content the response text content
     * @return list of parsed tool calls (empty if none found)
     */
    static List<ToolCall> extractFromContent(String content) {
        if (content == null || !content.contains(TOOL_CALL_MARKER)) {
            return Collections.emptyList();
        }

        List<ToolCall> results = new ArrayList<>();
        Matcher blockMatcher = TOOL_CALL_BLOCK_PATTERN.matcher(content);

        while (blockMatcher.find()) {
            String block = blockMatcher.group(1).trim();
            ToolCall call = parseBlock(block);
            if (call != null) {
                results.add(call);
            }
        }

        if (!results.isEmpty()) {
            LOG.info("Content fallback: extracted %d tool call(s) from XML in content", results.size()); //$NON-NLS-1$
        }

        return results;
    }

    /**
     * Strips all {@code <tool_call>...</tool_call>} blocks from content
     * so the user doesn't see raw XML.
     *
     * @param content the response text content
     * @return content with tool call blocks removed
     */
    static String stripToolCallBlocks(String content) {
        if (content == null || !content.contains(TOOL_CALL_MARKER)) {
            return content;
        }
        return TOOL_CALL_BLOCK_PATTERN.matcher(content).replaceAll("").trim(); //$NON-NLS-1$
    }

    /**
     * Checks if content contains tool call XML markers.
     */
    static boolean hasToolCallMarkers(String content) {
        return content != null && content.contains(TOOL_CALL_MARKER);
    }

    /**
     * Parses a single tool call block (content between &lt;tool_call&gt; tags).
     */
    private static ToolCall parseBlock(String block) {
        if (block == null || block.isEmpty()) {
            return null;
        }

        // Try Format A: qwen-coder XML format
        if (block.contains("<function=")) { //$NON-NLS-1$
            return parseQwenCoderFormat(block);
        }

        // Try Format B: qwen-vl JSON format
        if (block.startsWith("{")) { //$NON-NLS-1$
            return parseJsonFormat(block);
        }

        LOG.debug("Content fallback: unrecognized tool call format: %s", //$NON-NLS-1$
                block.length() > 100 ? block.substring(0, 100) + "..." : block); //$NON-NLS-1$
        return null;
    }

    /**
     * Parses qwen-coder XML format:
     * <pre>
     * &lt;function=tool_name&gt;
     * &lt;parameter=key1&gt;value1&lt;/parameter&gt;
     * &lt;parameter=key2&gt;value2&lt;/parameter&gt;
     * &lt;/function&gt;
     * </pre>
     */
    private static ToolCall parseQwenCoderFormat(String block) {
        Matcher nameMatcher = FUNCTION_NAME_PATTERN.matcher(block);
        if (!nameMatcher.find()) {
            LOG.debug("Content fallback: could not extract function name from qwen-coder format"); //$NON-NLS-1$
            return null;
        }

        String functionName = nameMatcher.group(1).trim();
        if (functionName.isEmpty()) {
            return null;
        }

        // Extract parameters
        StringBuilder argsJson = new StringBuilder();
        argsJson.append('{');

        Matcher paramMatcher = PARAMETER_PATTERN.matcher(block);
        boolean first = true;
        while (paramMatcher.find()) {
            if (!first) {
                argsJson.append(',');
            }
            String key = paramMatcher.group(1).trim();
            String value = paramMatcher.group(2).trim();
            argsJson.append('"').append(escapeJsonString(key)).append("\":"); //$NON-NLS-1$
            argsJson.append('"').append(escapeJsonString(value)).append('"');
            first = false;
        }

        argsJson.append('}');

        String id = "qwen_content_" + UUID.randomUUID().toString().substring(0, 8); //$NON-NLS-1$
        return new ToolCall(id, functionName, argsJson.toString());
    }

    /**
     * Parses qwen-vl JSON format:
     * <pre>
     * {"name": "tool_name", "arguments": {"key": "value"}}
     * </pre>
     */
    private static ToolCall parseJsonFormat(String block) {
        // Attempt repair if truncated
        String json = JsonRepairUtil.isComplete(block) ? block : JsonRepairUtil.repair(block);
        if (!JsonRepairUtil.isComplete(json)) {
            LOG.debug("Content fallback: could not repair JSON tool call: %s", //$NON-NLS-1$
                    block.length() > 80 ? block.substring(0, 80) + "..." : block); //$NON-NLS-1$
            return null;
        }

        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String name = obj.has("name") && !obj.get("name").isJsonNull() //$NON-NLS-1$ //$NON-NLS-2$
                    ? obj.get("name").getAsString() : null; //$NON-NLS-1$
            if (name == null || name.isBlank()) {
                return null;
            }

            String arguments = "{}"; //$NON-NLS-1$
            if (obj.has("arguments") && !obj.get("arguments").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                com.google.gson.JsonElement argsEl = obj.get("arguments"); //$NON-NLS-1$
                if (argsEl.isJsonObject()) {
                    arguments = argsEl.toString();
                } else if (argsEl.isJsonPrimitive()) {
                    // Sometimes arguments are serialized as a JSON string
                    arguments = argsEl.getAsString();
                }
            }

            String id = "qwen_content_" + UUID.randomUUID().toString().substring(0, 8); //$NON-NLS-1$
            return new ToolCall(id, name, arguments);

        } catch (Exception e) {
            LOG.debug("Content fallback: failed to parse JSON tool call: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Escapes special characters for JSON string values.
     */
    private static String escapeJsonString(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value
                .replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "\\\"") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\n", "\\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\r", "\\r") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\t", "\\t"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
