package com.codepilot1c.core.edt.ast;

import java.util.Locale;
import java.util.Map;

/**
 * Request for metadata index scan.
 */
public record MetadataIndexRequest(
        String projectName,
        String scope,
        String nameContains,
        int limit,
        String language
) {
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    public static MetadataIndexRequest fromParameters(Map<String, Object> parameters) {
        String projectName = asString(parameters.get("projectName")); //$NON-NLS-1$
        String scope = asString(parameters.get("scope")); //$NON-NLS-1$
        String nameContains = asString(parameters.get("nameContains")); //$NON-NLS-1$
        int limit = asInt(parameters.get("limit"), DEFAULT_LIMIT); //$NON-NLS-1$
        String language = asString(parameters.get("language")); //$NON-NLS-1$
        MetadataIndexRequest request = new MetadataIndexRequest(projectName, scope, nameContains, limit, language);
        request.validate();
        return request;
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "limit must be between 1 and " + MAX_LIMIT, false); //$NON-NLS-1$
        }
    }

    public String normalizedScope() {
        if (scope == null || scope.isBlank()) {
            return "all"; //$NON-NLS-1$
        }
        return scope.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedNameContains() {
        if (nameContains == null || nameContains.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        return nameContains.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedLanguage() {
        if (language == null || language.isBlank()) {
            return null;
        }
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
