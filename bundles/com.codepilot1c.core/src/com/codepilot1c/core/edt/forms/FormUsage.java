package com.codepilot1c.core.edt.forms;

import java.util.Locale;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Semantic role of a managed form for a metadata owner.
 */
public enum FormUsage {
    OBJECT,
    LIST,
    CHOICE,
    AUXILIARY;

    public static FormUsage fromString(String value) {
        if (value == null || value.isBlank()) {
            return AUXILIARY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "object", "item", "element", "формаэлемента" -> OBJECT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "list", "формасписка" -> LIST; //$NON-NLS-1$ //$NON-NLS-2$
            case "choice", "формавыбора" -> CHOICE; //$NON-NLS-1$ //$NON-NLS-2$
            case "auxiliary", "aux", "additional", "дополнительная", "default" -> AUXILIARY; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_FORM_USAGE,
                    "Unsupported form usage: " + value, false); //$NON-NLS-1$
        };
    }

    public static FormUsage fromOptionalString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return fromString(value);
    }
}
