package com.codepilot1c.core.edt.metadata;

import java.util.List;
import java.util.Map;

/**
 * Request for nested metadata creation.
 */
public record AddMetadataChildRequest(
        String projectName,
        String parentFqn,
        MetadataChildKind childKind,
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
        if (parentFqn == null || parentFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                    "parentFqn is required", false); //$NON-NLS-1$
        }
        if (childKind == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "childKind is required", false); //$NON-NLS-1$
        }
        if (!hasSingleName() && !hasBatchChildren()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Invalid metadata child name: " + name, false); //$NON-NLS-1$
        }
    }

    public boolean hasSingleName() {
        return MetadataNameValidator.isValidName(name);
    }

    @SuppressWarnings("unchecked")
    public boolean hasBatchChildren() {
        if (properties == null || properties.isEmpty()) {
            return false;
        }
        Object rawChildren = properties.get("children"); //$NON-NLS-1$
        if (!(rawChildren instanceof List<?> children) || children.isEmpty()) {
            return false;
        }
        for (Object entry : children) {
            if (!(entry instanceof Map<?, ?> item)) {
                return false;
            }
            Object rawName = item.get("name"); //$NON-NLS-1$
            String childName = rawName == null ? null : String.valueOf(rawName);
            if (!MetadataNameValidator.isValidName(childName)) {
                return false;
            }
        }
        return true;
    }
}
