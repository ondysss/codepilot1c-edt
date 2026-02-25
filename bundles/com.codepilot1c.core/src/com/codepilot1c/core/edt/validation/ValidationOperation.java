package com.codepilot1c.core.edt.validation;

import java.util.Locale;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Supported mutation operations that require pre-validation.
 */
public enum ValidationOperation {
    CREATE_METADATA("create_metadata"), //$NON-NLS-1$
    CREATE_FORM("create_form"), //$NON-NLS-1$
    APPLY_FORM_RECIPE("apply_form_recipe"), //$NON-NLS-1$
    EXTERNAL_CREATE_REPORT("external_create_report"), //$NON-NLS-1$
    EXTERNAL_CREATE_PROCESSING("external_create_processing"), //$NON-NLS-1$
    EXTENSION_CREATE_PROJECT("extension_create_project"), //$NON-NLS-1$
    EXTENSION_ADOPT_OBJECT("extension_adopt_object"), //$NON-NLS-1$
    EXTENSION_SET_PROPERTY_STATE("extension_set_property_state"), //$NON-NLS-1$
    DCS_CREATE_MAIN_SCHEMA("dcs_create_main_schema"), //$NON-NLS-1$
    DCS_UPSERT_QUERY_DATASET("dcs_upsert_query_dataset"), //$NON-NLS-1$
    DCS_UPSERT_PARAMETER("dcs_upsert_parameter"), //$NON-NLS-1$
    DCS_UPSERT_CALCULATED_FIELD("dcs_upsert_calculated_field"), //$NON-NLS-1$
    ADD_METADATA_CHILD("add_metadata_child"), //$NON-NLS-1$
    UPDATE_METADATA("update_metadata"), //$NON-NLS-1$
    DELETE_METADATA("delete_metadata"), //$NON-NLS-1$
    MUTATE_FORM_MODEL("mutate_form_model"); //$NON-NLS-1$

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
