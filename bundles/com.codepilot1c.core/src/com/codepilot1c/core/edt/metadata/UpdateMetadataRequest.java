package com.codepilot1c.core.edt.metadata;

import java.util.Map;

/**
 * Request for metadata update operation.
 */
public record UpdateMetadataRequest(
        String projectName,
        String targetFqn,
        Map<String, Object> changes
) {
    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (targetFqn == null || targetFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "targetFqn is required", false); //$NON-NLS-1$
        }
        if (changes == null || changes.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "changes are required", false); //$NON-NLS-1$
        }
    }
}
