package com.codepilot1c.core.edt.ast;

import java.util.List;
import java.util.Collections;

public class CallHierarchyResult {
    
    private MethodInfo method;
    private List<MethodCallNode> callers = Collections.emptyList();
    private List<MethodCallNode> callees = Collections.emptyList();
    
    public CallHierarchyResult() {
    }
    
    public MethodInfo getMethod() {
        return method;
    }
    
    public void setMethod(MethodInfo method) {
        this.method = method;
    }
    
    public List<MethodCallNode> getCallers() {
        return callers;
    }
    
    public void setCallers(List<MethodCallNode> callers) {
        this.callers = callers != null ? callers : Collections.emptyList();
    }
    
    public List<MethodCallNode> getCallees() {
        return callees;
    }
    
    public void setCallees(List<MethodCallNode> callees) {
        this.callees = callees != null ? callees : Collections.emptyList();
    }
}
