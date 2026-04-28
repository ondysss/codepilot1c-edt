package com.codepilot1c.core.edt.ast;

import java.util.List;
import java.util.Collections;

public class MethodInfo {
    private String fqn;
    private String name;
    private String moduleFqn;
    private boolean export;
    private int startLine;
    private int endLine;
    private String signature;
    private String returnType;
    
    public MethodInfo() {
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
    
    public String getModuleFqn() {
        return moduleFqn;
    }
    
    public void setModuleFqn(String moduleFqn) {
        this.moduleFqn = moduleFqn;
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
