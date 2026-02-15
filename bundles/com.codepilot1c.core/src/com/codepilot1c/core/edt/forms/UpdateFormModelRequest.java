package com.codepilot1c.core.edt.forms;

import java.util.List;
import java.util.Map;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for headless managed form model mutation.
 */
public record UpdateFormModelRequest(
        String projectName,
        String formFqn,
        List<Map<String, Object>> operations
) {
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
        if (operations == null || operations.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "operations must not be empty", false); //$NON-NLS-1$
        }
        for (Map<String, Object> operation : operations) {
            if (operation == null || operation.isEmpty()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "operation must be an object", false); //$NON-NLS-1$
            }
            Object op = operation.get("op"); //$NON-NLS-1$
            if (op == null || String.valueOf(op).isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "operation must contain non-empty 'op' field", false); //$NON-NLS-1$
            }
        }
    }
}
