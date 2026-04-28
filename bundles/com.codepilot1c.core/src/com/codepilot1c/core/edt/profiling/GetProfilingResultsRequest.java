/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.profiling;

import java.util.Locale;
import java.util.Map;

/**
 * Request to read accumulated EDT profiling results.
 */
public record GetProfilingResultsRequest(String moduleFilter, int minFrequency, int maxLinesPerModule) {

    private static final int DEFAULT_MIN_FREQUENCY = 1;
    private static final int DEFAULT_MAX_LINES_PER_MODULE = 200;
    private static final int HARD_MAX_LINES_PER_MODULE = 1000;

    public static GetProfilingResultsRequest fromParameters(Map<String, Object> parameters) {
        String moduleFilter = optionalString(parameters, "moduleFilter", "module_filter"); //$NON-NLS-1$ //$NON-NLS-2$
        int minFrequency = boundedInt(parameters, DEFAULT_MIN_FREQUENCY, 1, Integer.MAX_VALUE,
                "minFrequency", "min_frequency"); //$NON-NLS-1$ //$NON-NLS-2$
        int maxLinesPerModule = boundedInt(parameters, DEFAULT_MAX_LINES_PER_MODULE, 1,
                HARD_MAX_LINES_PER_MODULE, "maxLinesPerModule", "max_lines_per_module"); //$NON-NLS-1$ //$NON-NLS-2$
        return new GetProfilingResultsRequest(moduleFilter, minFrequency, maxLinesPerModule);
    }

    boolean matchesModule(String moduleName) {
        if (moduleFilter == null || moduleFilter.isBlank()) {
            return true;
        }
        String candidate = moduleName == null ? "" : moduleName; //$NON-NLS-1$
        return candidate.toLowerCase(Locale.ROOT).contains(moduleFilter.toLowerCase(Locale.ROOT));
    }

    private static String optionalString(Map<String, Object> parameters, String primaryKey, String aliasKey) {
        Object value = value(parameters, primaryKey, aliasKey);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static int boundedInt(Map<String, Object> parameters, int defaultValue, int min, int max,
            String primaryKey, String aliasKey) {
        Object value = value(parameters, primaryKey, aliasKey);
        int parsed = defaultValue;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static Object value(Map<String, Object> parameters, String primaryKey, String aliasKey) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(primaryKey);
        return value != null ? value : parameters.get(aliasKey);
    }
}
