package com.codepilot1c.core.edt.metadata;

import java.util.Map;

/**
 * Request for top-level metadata creation.
 */
public record CreateMetadataRequest(
        String projectName,
        MetadataKind kind,
        String name,
        String synonym,
        String comment,
        Map<String, Object> properties
) {
    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (kind == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "kind is required", false); //$NON-NLS-1$
        }
        if (!MetadataNameValidator.isValidName(name)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Invalid metadata name: " + name, false); //$NON-NLS-1$
        }
    }
}
