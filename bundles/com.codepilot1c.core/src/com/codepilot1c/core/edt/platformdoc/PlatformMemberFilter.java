package com.codepilot1c.core.edt.platformdoc;

import java.util.Locale;

/**
 * Member filter for platform documentation query.
 */
public enum PlatformMemberFilter {
    METHODS,
    PROPERTIES,
    ALL;

    public static PlatformMemberFilter fromString(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "methods", "method", "методы", "метод" -> METHODS; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "properties", "property", "свойства", "свойство" -> PROPERTIES; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "all", "все" -> ALL; //$NON-NLS-1$ //$NON-NLS-2$
            default -> throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.INVALID_REQUEST,
                    "Unsupported member filter: " + value, false); //$NON-NLS-1$
        };
    }
}
