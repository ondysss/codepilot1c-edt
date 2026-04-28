/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.Map;

/**
 * Request for starting/stopping profiling on a debug target.
 */
public class StartProfilingRequest {

    private final String projectName;
    private final Boolean enabled;

    public StartProfilingRequest(String projectName, Boolean enabled) {
        this.projectName = projectName;
        this.enabled = enabled;
    }

    public static StartProfilingRequest fromParameters(Map<String, Object> parameters) {
        if (parameters == null) {
            return new StartProfilingRequest(null, null);
        }
        String projectName = toString(parameters.get("projectName")); //$NON-NLS-1$
        Boolean enabled = toBoolean(parameters.get("enabled")); //$NON-NLS-1$
        return new StartProfilingRequest(projectName, enabled);
    }

    private static String toString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if (text.equals("true") || text.equals("1")) { //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.TRUE;
        }
        if (text.equals("false") || text.equals("0")) { //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.FALSE;
        }
        return null;
    }

    public void validate() {
        if (projectName == null || projectName.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "projectName is required", false); //$NON-NLS-1$
        }
    }

    public String getProjectName() {
        return projectName;
    }

    public Boolean getEnabled() {
        return enabled;
    }
}
