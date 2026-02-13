package com.codepilot1c.core.edt.validation;

import java.util.Map;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for pre-validation of metadata mutation.
 */
public record ValidationRequest(
        String projectName,
        ValidationOperation operation,
        Map<String, Object> payload
) {
    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "project is required", false); //$NON-NLS-1$
        }
        if (operation == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "operation is required", false); //$NON-NLS-1$
        }
        if (payload == null || payload.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "payload is required", false); //$NON-NLS-1$
        }
    }
}
