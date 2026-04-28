package com.codepilot1c.core.edt.ast;

public class ModuleStructureRequest {
    
    private final String projectName;
    private final String moduleFqn;
    private final boolean full;
    
    public ModuleStructureRequest(String projectName, String moduleFqn, boolean full) {
        this.projectName = projectName;
        this.moduleFqn = moduleFqn;
        this.full = full;
    }
    
    public String getProjectName() {
        return projectName;
    }
    
    public String getModuleFqn() {
        return moduleFqn;
    }
    
    public boolean isFull() {
        return full;
    }
    
    public static ModuleStructureRequest fromParameters(java.util.Map<String, Object> parameters) {
        String projectName = (String) parameters.get("projectName");
        String moduleFqn = (String) parameters.get("moduleFqn");
        boolean full = parameters.containsKey("full") && Boolean.TRUE.equals(parameters.get("full"));
        return new ModuleStructureRequest(projectName, moduleFqn, full);
    }
}
