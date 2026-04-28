package com.codepilot1c.core.edt.ast;

import java.util.List;
import java.util.Collections;

public class ModuleStructureResult {
    
    private List<ModuleSection> sections = Collections.emptyList();
    private List<String> exportedProcedures = Collections.emptyList();
    
    public ModuleStructureResult() {
    }
    
    public List<ModuleSection> getSections() {
        return sections;
    }
    
    public void setSections(List<ModuleSection> sections) {
        this.sections = sections != null ? sections : Collections.emptyList();
    }
    
    public List<String> getExportedProcedures() {
        return exportedProcedures;
    }
    
    public void setExportedProcedures(List<String> exportedProcedures) {
        this.exportedProcedures = exportedProcedures != null ? exportedProcedures : Collections.emptyList();
    }
    
    public static class ModuleSection {
        private String type;
        private int startLine;
        private int endLine;
        private List<MethodInfo> methods = Collections.emptyList();
        
        public ModuleSection() {
        }
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public int getStartLine() {
            return startLine;
        }
        
        public void setStartLine(int startLine) {
            this.startLine = startLine;
        }
        
        public int getEndLine() {
            return endLine;
        }
        
        public void setEndLine(int endLine) {
            this.endLine = endLine;
        }
        
        public List<MethodInfo> getMethods() {
            return methods;
        }
        
        public void setMethods(List<MethodInfo> methods) {
            this.methods = methods != null ? methods : Collections.emptyList();
        }
    }
    
    public static class MethodInfo {
        private String name;
        private boolean export;
        private int startLine;
        private int endLine;
        private String signature;
        private String returnType;
        
        public MethodInfo() {
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public boolean isExport() {
            return export;
        }
        
        public void setExport(boolean export) {
            this.export = export;
        }
        
        public int getStartLine() {
            return startLine;
        }
        
        public void setStartLine(int startLine) {
            this.startLine = startLine;
        }
        
        public int getEndLine() {
            return endLine;
        }
        
        public void setEndLine(int endLine) {
            this.endLine = endLine;
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
    }
}
