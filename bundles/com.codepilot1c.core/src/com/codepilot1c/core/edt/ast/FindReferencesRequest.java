package com.codepilot1c.core.edt.ast;

import java.util.Map;

/**
 * Request for finding references of metadata object.
 */
public class FindReferencesRequest {

    private final String projectName;
    private final String objectFqn;
    private final int limit;

    public FindReferencesRequest(String projectName, String objectFqn, int limit) {
        this.projectName = projectName;
        this.objectFqn = objectFqn;
        this.limit = limit;
    }

    public static FindReferencesRequest fromParameters(Map<String, Object> parameters) {
        String projectName = toString(parameters.get("projectName")); //$NON-NLS-1$
        String objectFqn = toString(parameters.get("objectFqn")); //$NON-NLS-1$
        int limit = clamp(toInt(parameters.get("limit"), 100), 1, 1000); //$NON-NLS-1$
        return new FindReferencesRequest(projectName, objectFqn, limit);
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
        if (objectFqn == null || objectFqn.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "objectFqn is required", false); //$NON-NLS-1$
        }
    }

    public String getProjectName() {
        return projectName;
    }

    public String getObjectFqn() {
        return objectFqn;
    }

    public int getLimit() {
        return limit;
    }
}
