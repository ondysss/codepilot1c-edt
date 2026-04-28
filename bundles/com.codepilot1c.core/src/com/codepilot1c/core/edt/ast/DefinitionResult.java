package com.codepilot1c.core.edt.ast;

public class DefinitionResult {
    
    private boolean found;
    private String fqn;
    private String name;
    private String type;
    private String fileUri;
    private PositionRange range;
    private String containingModule;
    private String signature;
    
    public DefinitionResult() {
    }
    
    public boolean hasDefinition() {
        return found;
    }
    
    public boolean isFound() {
        return found;
    }
    
    public void setFound(boolean found) {
        this.found = found;
    }
    
    public String getFqn() {
        return fqn;
    }
    
    public void setFqn(String fqn) {
        this.fqn = fqn;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getFileUri() {
        return fileUri;
    }
    
    public void setFileUri(String fileUri) {
        this.fileUri = fileUri;
    }
    
    public PositionRange getRange() {
        return range;
    }
    
    public void setRange(PositionRange range) {
        this.range = range;
    }
    
    public String getContainingModule() {
        return containingModule;
    }
    
    public void setContainingModule(String containingModule) {
        this.containingModule = containingModule;
    }
    
    public String getSignature() {
        return signature;
    }
    
    public void setSignature(String signature) {
        this.signature = signature;
    }
    
    public static class PositionRange {
        private int startLine;
        private int startColumn;
        private int endLine;
        private int endColumn;
        
        public PositionRange() {
        }
        
        public int getStartLine() {
            return startLine;
        }
        
        public void setStartLine(int startLine) {
            this.startLine = startLine;
        }
        
        public int getStartColumn() {
            return startColumn;
        }
        
        public void setStartColumn(int startColumn) {
            this.startColumn = startColumn;
        }
        
        public int getEndLine() {
            return endLine;
        }
        
        public void setEndLine(int endLine) {
            this.endLine = endLine;
        }
        
        public int getEndColumn() {
            return endColumn;
        }
        
        public void setEndColumn(int endColumn) {
            this.endColumn = endColumn;
        }
    }
}
