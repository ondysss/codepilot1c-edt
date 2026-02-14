package com.codepilot1c.core.edt.metadata;

/**
 * Request to ensure module artifact (*.bsl) exists for metadata object.
 */
public record EnsureModuleArtifactRequest(
        String projectName,
        String objectFqn,
        ModuleArtifactKind moduleKind,
        boolean createIfMissing,
        String initialContent
) {
    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "objectFqn is required", false); //$NON-NLS-1$
        }
        if (moduleKind == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "moduleKind is required", false); //$NON-NLS-1$
        }
    }
}
