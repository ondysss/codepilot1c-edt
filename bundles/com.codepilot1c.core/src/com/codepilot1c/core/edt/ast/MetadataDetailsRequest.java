package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request for metadata details.
 */
public class MetadataDetailsRequest {

    private final String projectName;
    private final List<String> objectFqns;
    private final boolean full;
    private final String language;

    public MetadataDetailsRequest(String projectName, List<String> objectFqns, boolean full, String language) {
        this.projectName = projectName;
        this.objectFqns = new ArrayList<>(objectFqns != null ? objectFqns : List.of());
        this.full = full;
        this.language = language;
    }

    public static MetadataDetailsRequest fromParameters(Map<String, Object> parameters) {
        String projectName = toString(parameters.get("projectName")); //$NON-NLS-1$
        List<String> objectFqns = toStringList(parameters.get("objectFqns")); //$NON-NLS-1$
        boolean full = toBoolean(parameters.get("full")); //$NON-NLS-1$
        String language = toString(parameters.get("language")); //$NON-NLS-1$
        return new MetadataDetailsRequest(projectName, objectFqns, full, language);
    }

    private static String toString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String text = toString(item);
                if (text != null) {
                    result.add(text);
                }
            }
            return result;
        }
        String single = toString(value);
        if (single == null) {
            return List.of();
        }
        return List.of(single);
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool.booleanValue();
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)); //$NON-NLS-1$
    }

    public void validate() {
        if (projectName == null || projectName.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (objectFqns.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "objectFqns is required", false); //$NON-NLS-1$
        }
    }

    public String getProjectName() {
        return projectName;
    }

    public List<String> getObjectFqns() {
        return objectFqns;
    }

    public boolean isFull() {
        return full;
    }

    public String getLanguage() {
        return language;
    }
}
