/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DebugRequestSupport {

    private DebugRequestSupport() {
    }

    static String string(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    static Integer integer(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = string(parameters, key);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long longValue(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = string(parameters, key);
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Boolean bool(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = string(parameters, key);
        if (text == null) {
            return null;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "да" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "false", "0", "no", "нет" -> Boolean.FALSE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> null;
        };
    }

    static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required"); //$NON-NLS-1$
        }
    }

    static void requirePositive(Integer value, String name) {
        if (value == null || value.intValue() <= 0) {
            throw invalid(name + " must be a positive integer"); //$NON-NLS-1$
        }
    }

    static void requirePositive(Long value, String name) {
        if (value == null || value.longValue() <= 0) {
            throw invalid(name + " must be a positive integer"); //$NON-NLS-1$
        }
    }

    static String normalizeOneOf(String value, String name, Set<String> allowed) {
        require(value, name);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw invalid(name + " must be one of " + allowed); //$NON-NLS-1$
        }
        return normalized;
    }

    static EdtAstException invalid(String message) {
        return new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT, message, false);
    }
}
