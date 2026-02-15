package com.codepilot1c.core.edt.forms;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for headless inspection of managed form layout.
 */
public record InspectFormLayoutRequest(
        String projectName,
        String formFqn,
        boolean includeProperties,
        boolean includeTitles,
        boolean includeInvisible,
        int maxDepth,
        int maxItems
) {
    private static final int DEFAULT_MAX_DEPTH = 12;
    private static final int DEFAULT_MAX_ITEMS = 2000;
    private static final int MIN_MAX_DEPTH = 1;
    private static final int MAX_MAX_DEPTH = 64;
    private static final int MIN_MAX_ITEMS = 1;
    private static final int MAX_MAX_ITEMS = 10000;

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (formFqn == null || formFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "formFqn is required", false); //$NON-NLS-1$
        }
        if (effectiveMaxDepth() < MIN_MAX_DEPTH || effectiveMaxDepth() > MAX_MAX_DEPTH) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "maxDepth must be between " + MIN_MAX_DEPTH + " and " + MAX_MAX_DEPTH, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (effectiveMaxItems() < MIN_MAX_ITEMS || effectiveMaxItems() > MAX_MAX_ITEMS) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "maxItems must be between " + MIN_MAX_ITEMS + " and " + MAX_MAX_ITEMS, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    public int effectiveMaxDepth() {
        return maxDepth <= 0 ? DEFAULT_MAX_DEPTH : maxDepth;
    }

    public int effectiveMaxItems() {
        return maxItems <= 0 ? DEFAULT_MAX_ITEMS : maxItems;
    }
}
