package com.codepilot1c.core.edt.lang;

/**
 * Result of fetching a BSL method body.
 */
public class BslMethodBodyResult {

    private final String projectName;
    private final String filePath;
    private final String name;
    private final String kind;
    private final int startLine;
    private final int endLine;
    private final String text;

    public BslMethodBodyResult(
            String projectName,
            String filePath,
            String name,
            String kind,
            int startLine,
            int endLine,
            String text) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.name = name;
        this.kind = kind;
        this.startLine = startLine;
        this.endLine = endLine;
        this.text = text;
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

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getText() {
        return text;
    }
}
