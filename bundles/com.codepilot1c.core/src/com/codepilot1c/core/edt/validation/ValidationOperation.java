package com.codepilot1c.core.edt.validation;

import java.util.Locale;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Supported mutation operations that require pre-validation.
 */
public enum ValidationOperation {
    CREATE_METADATA("create_metadata"), //$NON-NLS-1$
    ADD_METADATA_CHILD("add_metadata_child"), //$NON-NLS-1$
    UPDATE_METADATA("update_metadata"), //$NON-NLS-1$
    DELETE_METADATA("delete_metadata"); //$NON-NLS-1$

    private final String toolName;

    ValidationOperation(String toolName) {
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }

    public static ValidationOperation fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "operation is required", false); //$NON-NLS-1$
        }

        String normalized = value.toLowerCase(Locale.ROOT).trim();
        for (ValidationOperation operation : values()) {
            if (operation.toolName.equals(normalized)) {
                return operation;
            }
        }

        throw new MetadataOperationException(
                MetadataOperationCode.KNOWLEDGE_REQUIRED,
                "Unsupported operation for validation: " + value, false); //$NON-NLS-1$
    }
}
