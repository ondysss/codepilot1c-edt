package com.codepilot1c.core.edt.ql;

/**
 * Request to validate a 1C query-language text against an EDT project.
 */
public final class QlValidationRequest {

    private final String projectName;
    private final String queryText;
    private final boolean dcsMode;

    public QlValidationRequest(String projectName, String queryText, boolean dcsMode) {
        this.projectName = projectName;
        this.queryText = queryText;
        this.dcsMode = dcsMode;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getQueryText() {
        return queryText;
    }

    public boolean isDcsMode() {
        return dcsMode;
    }
}
