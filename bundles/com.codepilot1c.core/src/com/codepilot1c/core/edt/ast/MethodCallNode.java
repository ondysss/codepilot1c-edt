package com.codepilot1c.core.edt.ast;

import java.util.List;
import java.util.Collections;

public class MethodCallNode {
    private String methodFqn;
    private String moduleFqn;
    private int line;
    private int column;
    private int callCount;
    private List<MethodCallNode> children = Collections.emptyList();
    
    public MethodCallNode() {
    }
    
    public String getMethodFqn() {
        return methodFqn;
    }
    
    public void setMethodFqn(String methodFqn) {
        this.methodFqn = methodFqn;
    }
    
    public String getModuleFqn() {
        return moduleFqn;
    }
    
    public void setModuleFqn(String moduleFqn) {
        this.moduleFqn = moduleFqn;
    }
    
    public int getLine() {
        return line;
    }
    
    public void setLine(int line) {
        this.line = line;
    }
    
    public int getColumn() {
        return column;
    }
    
    public void setColumn(int column) {
        this.column = column;
    }
    
    public int getCallCount() {
        return callCount;
    }
    
    public void setCallCount(int callCount) {
        this.callCount = callCount;
    }
    
    public List<MethodCallNode> getChildren() {
        return children;
    }
    
    public void setChildren(List<MethodCallNode> children) {
        this.children = children != null ? children : Collections.emptyList();
    }
}
