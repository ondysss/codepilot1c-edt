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
 * Request for retrieving task markers from a project.
 */
public class GetTasksRequest {

    private final String projectName;
    private final int limit;

    public GetTasksRequest(String projectName, int limit) {
        this.projectName = projectName;
        this.limit = limit;
    }

    public static GetTasksRequest fromParameters(Map<String, Object> parameters) {
        if (parameters == null) {
            return new GetTasksRequest(null, 100);
        }
        String projectName = toString(parameters.get("projectName")); //$NON-NLS-1$
        int limit = clamp(toInt(parameters.get("limit"), 100), 1, 1000); //$NON-NLS-1$
        return new GetTasksRequest(projectName, limit);
    }

    private static String toString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    public int getLimit() {
        return limit;
    }
}
