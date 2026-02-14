package com.codepilot1c.core.edt.metadata;

/**
 * Result of ensuring module artifact for metadata object.
 */
public record ModuleArtifactResult(
        String projectName,
        String objectFqn,
        ModuleArtifactKind moduleKind,
        String modulePath,
        boolean created
) {
    public String formatForLlm() {
        StringBuilder sb = new StringBuilder();
        sb.append(created ? "Module artifact created successfully" : "Module artifact already exists"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append('\n');
        sb.append("Project: ").append(projectName); //$NON-NLS-1$
        sb.append('\n');
        sb.append("Object: ").append(objectFqn); //$NON-NLS-1$
        sb.append('\n');
        sb.append("Module kind: ").append(moduleKind); //$NON-NLS-1$
        sb.append('\n');
        sb.append("Path: ").append(modulePath); //$NON-NLS-1$
        return sb.toString();
    }
}
