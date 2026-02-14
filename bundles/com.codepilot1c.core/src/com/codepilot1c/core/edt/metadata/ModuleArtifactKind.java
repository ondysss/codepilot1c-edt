package com.codepilot1c.core.edt.metadata;

import java.util.Locale;

/**
 * Target module artifact kind for metadata object.
 */
public enum ModuleArtifactKind {
    AUTO,
    OBJECT,
    MANAGER,
    MODULE;

    public static ModuleArtifactKind fromString(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto" -> AUTO; //$NON-NLS-1$
            case "object", "objectmodule", "object_module" -> OBJECT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "manager", "managermodule", "manager_module" -> MANAGER; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "module", "form", "formmodule", "form_module" -> MODULE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported module kind: " + value, false); //$NON-NLS-1$
        };
    }
}
