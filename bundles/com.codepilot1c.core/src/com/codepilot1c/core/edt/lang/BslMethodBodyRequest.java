package com.codepilot1c.core.edt.lang;

import java.util.Locale;
import java.util.Map;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;

/**
 * Request to fetch a BSL method body.
 */
public class BslMethodBodyRequest {

    private final String projectName;
    private final String filePath;
    private final String name;
    private final String kind;
    private final int contextLines;
    private final Integer startLine;

    public BslMethodBodyRequest(
            String projectName,
            String filePath,
            String name,
            String kind,
            int contextLines,
            Integer startLine) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.name = name;
        this.kind = kind;
        this.contextLines = contextLines;
        this.startLine = startLine;
    }

    public static BslMethodBodyRequest fromParameters(Map<String, Object> parameters) {
        String projectName = asString(parameters.get("projectName")); //$NON-NLS-1$
        String filePath = asString(parameters.get("filePath")); //$NON-NLS-1$
        String name = asString(parameters.get("name")); //$NON-NLS-1$
        String kind = asString(parameters.get("kind")); //$NON-NLS-1$
        int contextLines = asInt(parameters.get("context_lines"), 0); //$NON-NLS-1$
        Integer startLine = asOptionalInt(parameters.get("start_line")); //$NON-NLS-1$
        return new BslMethodBodyRequest(projectName, filePath, name, kind, contextLines, startLine);
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
        if (name == null || name.isBlank()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "name is required", false); //$NON-NLS-1$
        }
        if (contextLines < 0) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "context_lines must be >= 0", false); //$NON-NLS-1$
        }
        if (startLine != null && startLine.intValue() < 1) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "start_line must be >= 1", false); //$NON-NLS-1$
        }
        String normalizedKind = normalizedKind();
        if (!"any".equals(normalizedKind) //$NON-NLS-1$
                && !"procedure".equals(normalizedKind) //$NON-NLS-1$
                && !"function".equals(normalizedKind)) { //$NON-NLS-1$
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "kind must be one of: any, procedure, function", false); //$NON-NLS-1$
        }
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public int getContextLines() {
        return contextLines;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public String normalizedKind() {
        if (kind == null || kind.isBlank()) {
            return "any"; //$NON-NLS-1$
        }
        return kind.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedName() {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
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

    private static Integer asOptionalInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
