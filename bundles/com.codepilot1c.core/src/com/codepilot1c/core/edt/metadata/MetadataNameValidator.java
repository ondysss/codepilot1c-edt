package com.codepilot1c.core.edt.metadata;

import java.util.regex.Pattern;

/**
 * Validates metadata object names.
 */
public final class MetadataNameValidator {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[\\p{L}][\\p{L}\\p{N}_]*$"); //$NON-NLS-1$

    private MetadataNameValidator() {
    }

    public static boolean isValidName(String name) {
        return name != null && !name.isBlank() && NAME_PATTERN.matcher(name).matches();
    }
}
