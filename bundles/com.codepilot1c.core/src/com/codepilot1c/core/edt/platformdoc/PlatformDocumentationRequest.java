package com.codepilot1c.core.edt.platformdoc;

import java.util.Map;

/**
 * Request for platform language documentation lookup.
 */
public record PlatformDocumentationRequest(
        String projectName,
        String typeName,
        String language,
        PlatformMemberFilter memberFilter,
        String contains,
        int limit,
        int offset
) {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    public static PlatformDocumentationRequest fromParameters(Map<String, Object> parameters) {
        String projectName = asString(parameters.get("project")); //$NON-NLS-1$
        String typeName = asString(parameters.get("type_name")); //$NON-NLS-1$
        String language = asString(parameters.get("language")); //$NON-NLS-1$
        PlatformMemberFilter filter = PlatformMemberFilter.fromString(asString(parameters.get("member_filter"))); //$NON-NLS-1$
        String contains = asString(parameters.get("contains")); //$NON-NLS-1$
        int limit = asInt(parameters.get("limit"), DEFAULT_LIMIT); //$NON-NLS-1$
        int offset = asInt(parameters.get("offset"), 0); //$NON-NLS-1$

        PlatformDocumentationRequest request = new PlatformDocumentationRequest(
                projectName,
                typeName,
                language,
                filter,
                contains,
                limit,
                offset);
        request.validate();
        return request;
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.INVALID_REQUEST,
                    "Parameter 'project' is required", false); //$NON-NLS-1$
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.INVALID_REQUEST,
                    "Parameter 'limit' must be between 1 and " + MAX_LIMIT, false); //$NON-NLS-1$
        }
        if (offset < 0) {
            throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.INVALID_REQUEST,
                    "Parameter 'offset' must be >= 0", false); //$NON-NLS-1$
        }
    }

    public String normalizedLanguage() {
        if (language == null || language.isBlank()) {
            return "ru"; //$NON-NLS-1$
        }
        return language.trim().toLowerCase();
    }

    public String normalizedContains() {
        return contains == null ? "" : contains.trim().toLowerCase(); //$NON-NLS-1$
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
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
