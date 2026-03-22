/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Parses JSON tool call arguments into typed parameter maps.
 *
 * <p>Extracted from {@code ToolRegistry} to separate JSON parsing concerns.
 * Handles both standard Gson parsing and a fallback simple parser for
 * malformed JSON from streaming LLM responses.</p>
 *
 * <p>Preserves whitespace in string values exactly as received,
 * which is critical for SEARCH/REPLACE edit blocks.</p>
 */
public class ToolArgumentParser {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolArgumentParser.class);

    /**
     * Parses JSON arguments string into a parameter map.
     *
     * @param json the JSON arguments string
     * @return parsed parameters (never null)
     */
    public Map<String, Object> parseArguments(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) { //$NON-NLS-1$
            return Collections.emptyMap();
        }

        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                LOG.warn("Tool arguments is not a JSON object: %s", json); //$NON-NLS-1$
                return Collections.emptyMap();
            }

            JsonObject obj = element.getAsJsonObject();
            Map<String, Object> result = new HashMap<>();

            for (String key : obj.keySet()) {
                JsonElement value = obj.get(key);
                result.put(key, convertJsonElement(value));
            }

            return result;

        } catch (JsonSyntaxException e) {
            String truncatedJson = json.length() > 200 ? json.substring(0, 200) + "..." : json; //$NON-NLS-1$
            LOG.warn("Failed to parse JSON arguments: %s, error: %s", truncatedJson, e.getMessage()); //$NON-NLS-1$
            return parseSimpleJson(json);
        }
    }

    private Object convertJsonElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else if (primitive.isNumber()) {
                double d = primitive.getAsDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                        return (int) d;
                    }
                    return primitive.getAsLong();
                }
                return d;
            } else {
                return primitive.getAsString();
            }
        }
        if (element.isJsonArray()) {
            var array = element.getAsJsonArray();
            List<Object> list = new ArrayList<>();
            for (JsonElement item : array) {
                list.add(convertJsonElement(item));
            }
            return list;
        }
        if (element.isJsonObject()) {
            var obj = element.getAsJsonObject();
            Map<String, Object> map = new HashMap<>();
            for (String key : obj.keySet()) {
                map.put(key, convertJsonElement(obj.get(key)));
            }
            return map;
        }
        return element.toString();
    }

    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) { //$NON-NLS-1$ //$NON-NLS-2$
            json = json.substring(1, json.length() - 1).trim();
        }

        if (json.isEmpty()) {
            return result;
        }

        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        List<String> pairs = new ArrayList<>();

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{' || c == '[') depth++;
                if (c == '}' || c == ']') depth--;
                if (c == ',' && depth == 0) {
                    pairs.add(current.toString().trim());
                    current = new StringBuilder();
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            pairs.add(current.toString().trim());
        }

        for (String pair : pairs) {
            int colonIndex = findFirstColonOutsideQuotes(pair);
            if (colonIndex > 0) {
                String key = pair.substring(0, colonIndex).trim();
                String value = pair.substring(colonIndex + 1).trim();

                if (key.startsWith("\"") && key.endsWith("\"")) { //$NON-NLS-1$ //$NON-NLS-2$
                    key = key.substring(1, key.length() - 1);
                }

                if (value.startsWith("\"") && value.endsWith("\"")) { //$NON-NLS-1$ //$NON-NLS-2$
                    String unescaped = value.substring(1, value.length() - 1);
                    unescaped = unescaped.replace("\\n", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace("\\r", "\r") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace("\\t", "\t") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace("\\\"", "\"") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace("\\\\", "\\"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.put(key, unescaped);
                } else if ("true".equals(value)) { //$NON-NLS-1$
                    result.put(key, Boolean.TRUE);
                } else if ("false".equals(value)) { //$NON-NLS-1$
                    result.put(key, Boolean.FALSE);
                } else if ("null".equals(value)) { //$NON-NLS-1$
                    result.put(key, null);
                } else {
                    try {
                        if (value.contains(".")) { //$NON-NLS-1$
                            result.put(key, Double.parseDouble(value));
                        } else {
                            result.put(key, Integer.parseInt(value));
                        }
                    } catch (NumberFormatException e) {
                        result.put(key, value);
                    }
                }
            }
        }

        return result;
    }

    private int findFirstColonOutsideQuotes(String str) {
        boolean inString = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '"' && (i == 0 || str.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (c == ':' && !inString) {
                return i;
            }
        }
        return -1;
    }
}
