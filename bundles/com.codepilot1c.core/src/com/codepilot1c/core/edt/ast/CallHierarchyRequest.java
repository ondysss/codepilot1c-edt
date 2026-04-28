package com.codepilot1c.core.edt.ast;

public class CallHierarchyRequest {
    
    private final String projectName;
    private final String methodFqn;
    private final String direction;
    private final int depth;
    
    public CallHierarchyRequest(String projectName, String methodFqn, String direction, int depth) {
        this.projectName = projectName;
        this.methodFqn = methodFqn;
        this.direction = direction;
        this.depth = depth;
    }
    
    public String getProjectName() {
        return projectName;
    }
    
    public String getMethodFqn() {
        return methodFqn;
    }
    
    public String getDirection() {
        return direction;
    }
    
    public int getDepth() {
        return depth;
    }
}
