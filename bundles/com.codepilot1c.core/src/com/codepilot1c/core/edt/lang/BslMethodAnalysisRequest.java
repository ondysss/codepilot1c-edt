package com.codepilot1c.core.edt.lang;

import java.util.Locale;
import java.util.Map;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;

/**
 * Request to analyze one BSL method structurally.
 */
public class BslMethodAnalysisRequest {

    private final String projectName;
    private final String filePath;
    private final String methodName;
    private final String kind;
    private final Integer startLine;

    public BslMethodAnalysisRequest(
            String projectName,
            String filePath,
            String methodName,
            String kind,
            Integer startLine) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.methodName = methodName;
        this.kind = kind;
        this.startLine = startLine;
    }

    public static BslMethodAnalysisRequest fromParameters(Map<String, Object> parameters) {
        return new BslMethodAnalysisRequest(
                asString(parameters.get("projectName")), //$NON-NLS-1$
                asString(parameters.get("filePath")), //$NON-NLS-1$
                firstNonBlank(asString(parameters.get("methodName")), asString(parameters.get("name"))), //$NON-NLS-1$ //$NON-NLS-2$
                asString(parameters.get("kind")), //$NON-NLS-1$
                asOptionalInt(parameters.get("start_line"))); //$NON-NLS-1$
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
        if (methodName == null || methodName.isBlank()) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "methodName is required", false); //$NON-NLS-1$
        }
        String normalizedKind = normalizedKind();
        if (!"any".equals(normalizedKind) //$NON-NLS-1$
                && !"procedure".equals(normalizedKind) //$NON-NLS-1$
                && !"function".equals(normalizedKind)) { //$NON-NLS-1$
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "kind must be one of: any, procedure, function", false); //$NON-NLS-1$
        }
        if (startLine != null && startLine.intValue() < 1) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "start_line must be >= 1", false); //$NON-NLS-1$
        }
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getMethodName() {
        return methodName;
    }

    public String normalizedMethodName() {
        return methodName == null ? "" : methodName.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    public String normalizedKind() {
        if (kind == null || kind.isBlank()) {
            return "any"; //$NON-NLS-1$
        }
        return kind.trim().toLowerCase(Locale.ROOT);
    }

    public Integer getStartLine() {
        return startLine;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer asOptionalInt(Object value) {
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf((int) Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
