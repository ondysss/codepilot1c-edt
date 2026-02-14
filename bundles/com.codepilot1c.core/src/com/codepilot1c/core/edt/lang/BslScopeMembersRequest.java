package com.codepilot1c.core.edt.lang;

import java.util.Locale;
import java.util.Map;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;

/**
 * Request for collecting BSL scope members at position.
 */
public class BslScopeMembersRequest {

    private final String projectName;
    private final String filePath;
    private final int line;
    private final int column;
    private final int limit;
    private final int offset;
    private final String contains;
    private final String language;

    public BslScopeMembersRequest(
            String projectName,
            String filePath,
            int line,
            int column,
            int limit,
            int offset,
            String contains,
            String language) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
        this.limit = limit;
        this.offset = offset;
        this.contains = contains;
        this.language = language;
    }

    public static BslScopeMembersRequest fromParameters(Map<String, Object> parameters) {
        String projectName = asString(parameters.get("projectName")); //$NON-NLS-1$
        String filePath = asString(parameters.get("filePath")); //$NON-NLS-1$
        int line = asInt(parameters.get("line"), 1); //$NON-NLS-1$
        int column = asInt(parameters.get("column"), 1); //$NON-NLS-1$
        int limit = clamp(asInt(parameters.get("limit"), 50), 1, 500); //$NON-NLS-1$
        int offset = Math.max(0, asInt(parameters.get("offset"), 0)); //$NON-NLS-1$
        String contains = asString(parameters.get("contains")); //$NON-NLS-1$
        String language = asString(parameters.get("language")); //$NON-NLS-1$
        return new BslScopeMembersRequest(projectName, filePath, line, column, limit, offset, contains, language);
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (filePath == null || filePath.isBlank()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "filePath is required", false); //$NON-NLS-1$
        }
        if (line < 1 || column < 1) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "line and column must be >= 1", false); //$NON-NLS-1$
        }
        if (limit < 1 || limit > 500) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "limit must be in range 1..500", false); //$NON-NLS-1$
        }
        if (offset < 0) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "offset must be >= 0", false); //$NON-NLS-1$
        }
    }

    public BslPositionRequest toPositionRequest() {
        return new BslPositionRequest(projectName, filePath, line, column);
    }

    public String getLanguageNormalized() {
        if (language == null || language.isBlank()) {
            return "ru"; //$NON-NLS-1$
        }
        return language.toLowerCase(Locale.ROOT);
    }

    public String getContainsNormalized() {
        if (contains == null || contains.isBlank()) {
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

    public String getLanguage() {
        return language;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int asInt(Object value, int defaultValue) {
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
}
