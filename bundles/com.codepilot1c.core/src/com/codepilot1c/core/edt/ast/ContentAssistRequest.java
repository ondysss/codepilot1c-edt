package com.codepilot1c.core.edt.ast;

import java.util.Locale;
import java.util.Map;

/**
 * Request for content assist in BSL.
 */
public class ContentAssistRequest {

    private final String projectName;
    private final String filePath;
    private final int line;
    private final int column;
    private final int limit;
    private final int offset;
    private final String contains;
    private final boolean extendedDocumentation;

    public ContentAssistRequest(String projectName, String filePath, int line, int column,
            int limit, int offset, String contains, boolean extendedDocumentation) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
        this.limit = limit;
        this.offset = offset;
        this.contains = contains;
        this.extendedDocumentation = extendedDocumentation;
    }

    public static ContentAssistRequest fromParameters(Map<String, Object> parameters) {
        String projectName = toString(parameters.get("projectName")); //$NON-NLS-1$
        String filePath = toString(parameters.get("filePath")); //$NON-NLS-1$
        int line = toInt(parameters.get("line"), 1); //$NON-NLS-1$
        int column = toInt(parameters.get("column"), 1); //$NON-NLS-1$
        int limit = clamp(toInt(parameters.get("limit"), 20), 1, 200); //$NON-NLS-1$
        int offset = Math.max(0, toInt(parameters.get("offset"), 0)); //$NON-NLS-1$
        String contains = toString(parameters.get("contains")); //$NON-NLS-1$
        boolean extendedDocumentation = toBoolean(parameters.get("extendedDocumentation")); //$NON-NLS-1$
        return new ContentAssistRequest(projectName, filePath, line, column, limit, offset, contains,
                extendedDocumentation);
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

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool.booleanValue();
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value).trim()); //$NON-NLS-1$
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void validate() {
        if (projectName == null || projectName.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (filePath == null || filePath.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "filePath is required", false); //$NON-NLS-1$
        }
        if (line < 1 || column < 1) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "line and column must be >= 1", false); //$NON-NLS-1$
        }
    }

    public String getContainsNormalized() {
        if (contains == null) {
            return null;
        }
        return contains.toLowerCase(Locale.ROOT);
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public int getLimit() {
        return limit;
    }

    public int getOffset() {
        return offset;
    }

    public String getContains() {
        return contains;
    }

    public boolean isExtendedDocumentation() {
        return extendedDocumentation;
    }
}
