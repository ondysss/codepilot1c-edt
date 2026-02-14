package com.codepilot1c.core.edt.lang;

import java.util.Map;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;

/**
 * BSL position request (project/file/line/column).
 */
public class BslPositionRequest {

    private final String projectName;
    private final String filePath;
    private final int line;
    private final int column;

    public BslPositionRequest(String projectName, String filePath, int line, int column) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
    }

    public static BslPositionRequest fromParameters(Map<String, Object> parameters) {
        String projectName = asString(parameters.get("projectName")); //$NON-NLS-1$
        String filePath = asString(parameters.get("filePath")); //$NON-NLS-1$
        int line = asInt(parameters.get("line"), 1); //$NON-NLS-1$
        int column = asInt(parameters.get("column"), 1); //$NON-NLS-1$
        return new BslPositionRequest(projectName, filePath, line, column);
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
}
