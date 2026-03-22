package com.codepilot1c.core.edt.lang;

import java.util.Map;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;

/**
 * Minimal request for a BSL module-scoped query.
 */
public class BslModuleRequest {

    private final String projectName;
    private final String filePath;

    public BslModuleRequest(String projectName, String filePath) {
        this.projectName = projectName;
        this.filePath = filePath;
    }

    public static BslModuleRequest fromParameters(Map<String, Object> parameters) {
        return new BslModuleRequest(
                asString(parameters.get("projectName")), //$NON-NLS-1$
                asString(parameters.get("filePath"))); //$NON-NLS-1$
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
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
