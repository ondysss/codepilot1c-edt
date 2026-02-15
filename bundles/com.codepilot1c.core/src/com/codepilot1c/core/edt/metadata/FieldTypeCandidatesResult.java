package com.codepilot1c.core.edt.metadata;

import java.util.List;

/**
 * Result of listing EDT type candidates for a metadata field.
 */
public record FieldTypeCandidatesResult(
        String projectName,
        String targetFqn,
        String fieldName,
        int total,
        List<FieldTypeCandidate> candidates
) {
    public String formatForLlm() {
        StringBuilder sb = new StringBuilder();
        sb.append("Field type candidates").append('\n'); //$NON-NLS-1$
        sb.append("Project: ").append(projectName).append('\n'); //$NON-NLS-1$
        sb.append("Target: ").append(targetFqn).append('\n'); //$NON-NLS-1$
        sb.append("Field: ").append(fieldName).append('\n'); //$NON-NLS-1$
        sb.append("Total: ").append(total).append('\n'); //$NON-NLS-1$
        for (FieldTypeCandidate candidate : candidates) {
            sb.append("- "); //$NON-NLS-1$
            sb.append(candidate.name());
            if (candidate.nameRu() != null && !candidate.nameRu().isBlank()
                    && !candidate.nameRu().equalsIgnoreCase(candidate.name())) {
                sb.append(" / ").append(candidate.nameRu()); //$NON-NLS-1$
            }
            if (candidate.typeClass() != null && !candidate.typeClass().isBlank()) {
                sb.append(" [class=").append(candidate.typeClass()).append(']'); //$NON-NLS-1$
            }
            if (candidate.simpleType()) {
                sb.append(" [simple]"); //$NON-NLS-1$
            }
            if (candidate.code() != null && !candidate.code().isBlank()) {
                sb.append(" code=").append(candidate.code()); //$NON-NLS-1$
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
