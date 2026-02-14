package com.codepilot1c.core.edt.lang;

/**
 * Symbol-at-position result.
 */
public class BslSymbolResult {

    private final String projectName;
    private final String filePath;
    private final int line;
    private final int column;
    private final int offset;
    private final String symbolKind;
    private final String symbolName;
    private final String symbolText;
    private final String elementClass;
    private final String elementUri;
    private final String containerClass;
    private final String containerName;
    private final int startLine;
    private final int startColumn;
    private final int endLine;
    private final int endColumn;

    public BslSymbolResult(
            String projectName,
            String filePath,
            int line,
            int column,
            int offset,
            String symbolKind,
            String symbolName,
            String symbolText,
            String elementClass,
            String elementUri,
            String containerClass,
            String containerName,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
        this.offset = offset;
        this.symbolKind = symbolKind;
        this.symbolName = symbolName;
        this.symbolText = symbolText;
        this.elementClass = elementClass;
        this.elementUri = elementUri;
        this.containerClass = containerClass;
        this.containerName = containerName;
        this.startLine = startLine;
        this.startColumn = startColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
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

    public int getOffset() {
        return offset;
    }

    public String getSymbolKind() {
        return symbolKind;
    }

    public String getSymbolName() {
        return symbolName;
    }

    public String getSymbolText() {
        return symbolText;
    }

    public String getElementClass() {
        return elementClass;
    }

    public String getElementUri() {
        return elementUri;
    }

    public String getContainerClass() {
        return containerClass;
    }

    public String getContainerName() {
        return containerName;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public int getEndLine() {
        return endLine;
    }

    public int getEndColumn() {
        return endColumn;
    }
}
