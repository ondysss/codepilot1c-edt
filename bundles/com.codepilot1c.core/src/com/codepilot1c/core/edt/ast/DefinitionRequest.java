package com.codepilot1c.core.edt.ast;

public class DefinitionRequest {
    
    private final String projectName;
    private final String fileUri;
    private final int line;
    private final int column;
    private final String symbolFqn;
    private final boolean useSymbolFqn;
    
    private DefinitionRequest(String projectName, String fileUri, int line, int column, String symbolFqn) {
        this.projectName = projectName;
        this.fileUri = fileUri;
        this.line = line;
        this.column = column;
        this.symbolFqn = symbolFqn;
        this.useSymbolFqn = symbolFqn != null && !symbolFqn.isEmpty();
    }
    
    public static DefinitionRequest fromPosition(String projectName, String fileUri, int line, int column) {
        return new DefinitionRequest(projectName, fileUri, line, column, null);
    }
    
    public static DefinitionRequest fromSymbolFqn(String projectName, String symbolFqn) {
        return new DefinitionRequest(projectName, null, 0, 0, symbolFqn);
    }
    
    public String getProjectName() {
        return projectName;
    }
    
    public String getFileUri() {
        return fileUri;
    }
    
    public int getLine() {
        return line;
    }
    
    public int getColumn() {
        return column;
    }
    
    public String getSymbolFqn() {
        return symbolFqn;
    }
    
    public boolean useSymbolFqn() {
        return useSymbolFqn;
    }
}
