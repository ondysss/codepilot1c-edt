/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Type-safe wrapper over tool parameters from LLM tool calls.
 *
 * <p>Provides typed access with clear error messages for missing
 * or invalid parameters.</p>
 */
public class ToolParameters {

    private final Map<String, Object> raw;

    public ToolParameters(Map<String, Object> parameters) {
        this.raw = parameters != null ? parameters : Collections.emptyMap();
    }

    /**
     * Returns the raw parameter map.
     */
    public Map<String, Object> getRaw() {
        return Collections.unmodifiableMap(raw);
    }

    /**
     * Gets a required string parameter.
     *
     * @param name parameter name
     * @return non-null string value
     * @throws ToolParameterException if missing or not a string
     */
    public String requireString(String name) {
        Object value = raw.get(name);
        if (value == null) {
            throw new ToolParameterException(
                    String.format("Required parameter '%s' is missing", name)); //$NON-NLS-1$
        }
        if (!(value instanceof String str)) {
            throw new ToolParameterException(
                    String.format("Parameter '%s' must be a string, got %s", //$NON-NLS-1$
                            name, value.getClass().getSimpleName()));
        }
        if (str.isBlank()) {
            throw new ToolParameterException(
                    String.format("Required parameter '%s' must not be blank", name)); //$NON-NLS-1$
        }
        return str;
    }

    /**
     * Gets an optional string parameter.
     *
     * @param name parameter name
     * @param defaultValue value if missing
     * @return string value or default
     */
    public String optString(String name, String defaultValue) {
        Object value = raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        return value instanceof String str ? str : String.valueOf(value);
    }

    /**
     * Gets a required integer parameter.
     *
     * @param name parameter name
     * @return integer value
     * @throws ToolParameterException if missing or not a number
     */
    public int requireInt(String name) {
        Object value = raw.get(name);
        if (value == null) {
            throw new ToolParameterException(
                    String.format("Required parameter '%s' is missing", name)); //$NON-NLS-1$
        }
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                throw new ToolParameterException(
                        String.format("Parameter '%s' must be an integer, got '%s'", name, str)); //$NON-NLS-1$
            }
        }
        throw new ToolParameterException(
                String.format("Parameter '%s' must be an integer, got %s", //$NON-NLS-1$
                        name, value.getClass().getSimpleName()));
    }

    /**
     * Gets an optional integer parameter.
     *
     * @param name parameter name
     * @param defaultValue value if missing
     * @return integer value or default
     */
    public int optInt(String name, int defaultValue) {
        Object value = raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Gets an optional boolean parameter.
     *
     * @param name parameter name
     * @param defaultValue value if missing
     * @return boolean value or default
     */
    public boolean optBoolean(String name, boolean defaultValue) {
        Object value = raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return "true".equalsIgnoreCase(str); //$NON-NLS-1$
        }
        return defaultValue;
    }

    /**
     * Gets an optional list of strings parameter.
     *
     * @param name parameter name
     * @return list of strings (never null)
     */
    @SuppressWarnings("unchecked")
    public List<String> optStringList(String name) {
        Object value = raw.get(name);
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * Checks if a parameter is present (non-null).
     */
    public boolean has(String name) {
        return raw.containsKey(name) && raw.get(name) != null;
    }

    /**
     * Exception for tool parameter validation errors.
     */
    public static class ToolParameterException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ToolParameterException(String message) {
            super(message);
        }
    }
}
