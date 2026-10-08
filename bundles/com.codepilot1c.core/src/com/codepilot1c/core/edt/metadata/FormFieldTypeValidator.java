package com.codepilot1c.core.edt.metadata;

import java.util.Locale;
import java.util.Set;

/**
 * Validates field types before adding a managed-form table column.
 *
 * <p>CHECK_BOX_FIELD is supported inside a Table. Its CheckBoxFieldExtInfo
 * must match the requested type; an incompatible ext-info causes SU107,
 * not the checkbox's table parent. Boolean INPUT_FIELD remains supported
 * but is an input control, so test clients cannot use checkbox actions on it.</p>
 */
public final class FormFieldTypeValidator {

    /** Field types whose table support is not enabled by this validator. */
    private static final Set<String> TABLE_INCOMPATIBLE_FIELD_TYPES = Set.of(
            "RADIOBUTTONFIELD", //$NON-NLS-1$
            "PROGRESSBARFIELD", //$NON-NLS-1$
            "TRACKBARFIELD"); //$NON-NLS-1$

    private FormFieldTypeValidator() {
    }

    /**
     * Returns {@code true} if the given field-type string is incompatible
     * with a Table parent (rejected by SU107 at platform level).
     *
     * @param fieldType raw {@code field_type} string from the agent payload
     *                  (case-insensitive, {@code _}/{@code -}/space ignored;
     *                  may be null/blank)
     * @return {@code true} if the platform would reject this field type
     *         inside a Table parent
     */
    public static boolean isIncompatibleWithTableParent(String fieldType) {
        if (fieldType == null) {
            return false;
        }
        String normalized = fieldType
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toUpperCase(Locale.ROOT);
        return TABLE_INCOMPATIBLE_FIELD_TYPES.contains(normalized);
    }

    /**
     * Builds the canonical "use INPUT_FIELD instead" error message.
     *
     * @param rawFieldType the raw field-type string the caller supplied
     *                     (echoed back verbatim)
     * @param fieldName field name from the operation, may be null
     * @return human-readable error message
     */
    public static String tableIncompatibleFieldTypeMessage(String rawFieldType, String fieldName) {
        StringBuilder sb = new StringBuilder();
        sb.append("field_type '").append(rawFieldType).append("' is not allowed inside a Table parent"); //$NON-NLS-1$ //$NON-NLS-2$
        if (fieldName != null && !fieldName.isBlank()) {
            sb.append(" (field name='").append(fieldName).append("')"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(": the 1C platform rejects it with SU107 'Illegal extension type for field type'."); //$NON-NLS-1$
        sb.append(" Use INPUT_FIELD, or CHECK_BOX_FIELD for a Boolean checkbox column."); //$NON-NLS-1$
        return sb.toString();
    }
}
