package com.codepilot1c.core.edt.metadata;

/**
 * Request for listing EDT-supported type candidates for a metadata field.
 */
public record FieldTypeCandidatesRequest(
        String projectName,
        String targetFqn,
        String fieldName,
        Integer limit
) {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

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
    }

    public String effectiveFieldName() {
        return fieldName == null || fieldName.isBlank() ? "type" : fieldName; //$NON-NLS-1$
    }

    public int effectiveLimit() {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit.intValue() < 1) {
            return 1;
        }
        if (limit.intValue() > MAX_LIMIT) {
            return MAX_LIMIT;
        }
        return limit.intValue();
    }
}
