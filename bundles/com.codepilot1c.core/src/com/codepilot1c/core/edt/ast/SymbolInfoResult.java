package com.codepilot1c.core.edt.ast;

import java.util.List;
import java.util.Collections;

public class SymbolInfoResult {
    
    private boolean found;
    private String fqn;
    private String name;
    private String type;
    private String kind;
    private String visibility;
    private String fileUri;
    private PositionRange range;
    private String containingModule;
    private String containingModuleOwner;
    private String signature;
    private String returnType;
    private List<ParameterInfo> parameters = Collections.emptyList();
    private String documentation;
    
    public SymbolInfoResult() {
    }
    
    public boolean hasInfo() {
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
    
    public String getKind() {
        return kind;
    }
    
    public void setKind(String kind) {
        this.kind = kind;
    }
    
    public String getVisibility() {
        return visibility;
    }
    
    public void setVisibility(String visibility) {
        this.visibility = visibility;
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
    
    public String getContainingModuleOwner() {
        return containingModuleOwner;
    }
    
    public void setContainingModuleOwner(String containingModuleOwner) {
        this.containingModuleOwner = containingModuleOwner;
    }
    
    public String getSignature() {
        return signature;
    }
    
    public void setSignature(String signature) {
        this.signature = signature;
    }
    
    public String getReturnType() {
        return returnType;
    }
    
    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }
    
    public List<ParameterInfo> getParameters() {
        return parameters;
    }
    
    public void setParameters(List<ParameterInfo> parameters) {
        this.parameters = parameters != null ? parameters : Collections.emptyList();
    }
    
    public String getDocumentation() {
        return documentation;
    }
    
    public void setDocumentation(String documentation) {
        this.documentation = documentation;
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
    
    public static class ParameterInfo {
        private String name;
        private String type;
        private boolean optional;
        
        public ParameterInfo() {
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
        
        public boolean isOptional() {
            return optional;
        }
        
        public void setOptional(boolean optional) {
            this.optional = optional;
        }
    }
}
