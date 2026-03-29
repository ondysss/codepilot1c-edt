package com.codepilot1c.core.edt.validation;

import java.util.Locale;
import java.util.Map;

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
    ENSURE_MODULE_ARTIFACT("ensure_module_artifact"), //$NON-NLS-1$
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
        return resolve(value, null);
    }

    public static ValidationOperation resolve(String value, Map<String, Object> payload) {
        if (value == null || value.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "operation is required", false); //$NON-NLS-1$
        }

        String normalized = value.toLowerCase(Locale.ROOT).trim();
        if ("external_manage".equals(normalized)) { //$NON-NLS-1$
            return resolveExternalManageCommand(payload);
        }
        if ("extension_manage".equals(normalized)) { //$NON-NLS-1$
            return resolveExtensionManageCommand(payload);
        }
        if ("dcs_manage".equals(normalized)) { //$NON-NLS-1$
            return resolveDcsManageCommand(payload);
        }
        for (ValidationOperation operation : values()) {
            if (operation.toolName.equals(normalized)) {
                return operation;
            }
        }

        throw new MetadataOperationException(
                MetadataOperationCode.KNOWLEDGE_REQUIRED,
                "Unsupported operation for validation: " + value, false); //$NON-NLS-1$
    }

    private static ValidationOperation resolveExternalManageCommand(Map<String, Object> payload) {
        return switch (normalizedCommand(payload, "external_manage")) { //$NON-NLS-1$
            case "create_report" -> EXTERNAL_CREATE_REPORT; //$NON-NLS-1$
            case "create_processing" -> EXTERNAL_CREATE_PROCESSING; //$NON-NLS-1$
            default -> throw unsupportedCompositeCommand("external_manage", payload); //$NON-NLS-1$
        };
    }

    private static ValidationOperation resolveExtensionManageCommand(Map<String, Object> payload) {
        return switch (normalizedCommand(payload, "extension_manage")) { //$NON-NLS-1$
            case "create" -> EXTENSION_CREATE_PROJECT; //$NON-NLS-1$
            case "adopt" -> EXTENSION_ADOPT_OBJECT; //$NON-NLS-1$
            case "set_state" -> EXTENSION_SET_PROPERTY_STATE; //$NON-NLS-1$
            default -> throw unsupportedCompositeCommand("extension_manage", payload); //$NON-NLS-1$
        };
    }

    private static ValidationOperation resolveDcsManageCommand(Map<String, Object> payload) {
        return switch (normalizedCommand(payload, "dcs_manage")) { //$NON-NLS-1$
            case "create_schema" -> DCS_CREATE_MAIN_SCHEMA; //$NON-NLS-1$
            case "upsert_dataset" -> DCS_UPSERT_QUERY_DATASET; //$NON-NLS-1$
            case "upsert_param" -> DCS_UPSERT_PARAMETER; //$NON-NLS-1$
            case "upsert_field" -> DCS_UPSERT_CALCULATED_FIELD; //$NON-NLS-1$
            default -> throw unsupportedCompositeCommand("dcs_manage", payload); //$NON-NLS-1$
        };
    }

    private static String normalizedCommand(Map<String, Object> payload, String operation) {
        if (payload == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "payload.command is required for " + operation, false); //$NON-NLS-1$
        }
        Object commandValue = payload.get("command"); //$NON-NLS-1$
        if (commandValue == null || String.valueOf(commandValue).isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "payload.command is required for " + operation, false); //$NON-NLS-1$
        }
        return String.valueOf(commandValue).toLowerCase(Locale.ROOT).trim();
    }

    private static MetadataOperationException unsupportedCompositeCommand(String operation, Map<String, Object> payload) {
        Object commandValue = payload == null ? null : payload.get("command"); //$NON-NLS-1$
        return new MetadataOperationException(
                MetadataOperationCode.KNOWLEDGE_REQUIRED,
                "Unsupported command for validation: " + operation + "." + commandValue, false); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
