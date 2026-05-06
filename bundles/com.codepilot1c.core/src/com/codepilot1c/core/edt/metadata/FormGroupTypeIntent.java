package com.codepilot1c.core.edt.metadata;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java helpers for interpreting {@code type} / {@code group_type}
 * values that callers pass to {@code mutate_form_model.add_group}.
 *
 * <p>Background: in the 1C form model, {@code Table}, {@code Pages}, and
 * {@code Page} are <em>different things</em>:</p>
 * <ul>
 *   <li>{@code Pages} and {@code Page} are FormGroup variants — the EMF
 *       enum {@code ManagedFormGroupType} exposes them as {@code PAGES}
 *       and {@code PAGE}, and the XML serialization is
 *       {@code <items xsi:type="form:FormGroup"><type>Pages</type>...} with
 *       the appropriate {@code PagesGroupExtInfo}/{@code PageGroupExtInfo}
 *       companions.  These can be created via the existing
 *       {@code add_group} dispatcher once the user picks a recognized
 *       enum value.</li>
 *   <li>{@code Table} is its own EMF model class, NOT a FormGroup variant.
 *       The XML serialization is {@code <items xsi:type="form:Table">...}
 *       with a {@code TableExtInfo} block.  {@code add_group type:"TABLE"}
 *       previously fell through to the {@code USUAL_GROUP} default and
 *       produced a UsualGroup that <em>looked</em> created — only runtime
 *       UI inspection or {@code inspect_form_layout} caught the silent
 *       downgrade.</li>
 * </ul>
 *
 * <p>This helper recognizes the three intents and returns a verdict the
 * caller uses to either dispatch normally or throw a clear error.</p>
 */
public final class FormGroupTypeIntent {

    /**
     * Possible classifications of a {@code type}/{@code group_type} value
     * supplied to {@code add_group}.
     */
    public enum Verdict {
        /** No type expressed — caller should fall back to the default. */
        UNSPECIFIED,
        /** Caller asked for a Table, which is NOT a FormGroup variant. */
        TABLE_NOT_A_GROUP,
        /** Caller used a recognized {@code ManagedFormGroupType} enum value. */
        RECOGNIZED_GROUP_TYPE,
        /** Caller used a value that does not match any recognized intent. */
        UNRECOGNIZED
    }

    /** Tokens that map to the Table EMF class (not a FormGroup variant). */
    private static final Set<String> TABLE_TOKENS = Set.of(
            "TABLE", //$NON-NLS-1$
            "FORMTABLE", //$NON-NLS-1$
            "DATATABLE"); //$NON-NLS-1$

    /**
     * Tokens (post-normalization) that match {@link
     * com._1c.g5.v8.dt.form.model.ManagedFormGroupType} enum values.
     * Normalization strips both {@code _} and {@code -}, so we list each
     * canonical name without separators.  The {@code EdtMetadataService}
     * resolver feeds the original raw value to {@code valueOf}, so the
     * underscore-bearing form remains the source of truth there — this
     * set only governs the verdict for the up-front classifier.
     */
    private static final Set<String> KNOWN_GROUP_TYPE_TOKENS = Set.of(
            "USUALGROUP", //$NON-NLS-1$
            "PAGES", //$NON-NLS-1$
            "PAGE", //$NON-NLS-1$
            "COLUMNGROUP", //$NON-NLS-1$
            "BUTTONGROUP", //$NON-NLS-1$
            "COMMANDBAR", //$NON-NLS-1$
            "AUTOCOMMANDBAR", //$NON-NLS-1$
            "POPUP", //$NON-NLS-1$
            "CONTEXTMENU", //$NON-NLS-1$
            "NAVIGATOR", //$NON-NLS-1$
            "ROWACTIONSPANEL", //$NON-NLS-1$
            "SELECTEDITEMSACTIONSPANEL"); //$NON-NLS-1$

    private FormGroupTypeIntent() {
    }

    /**
     * Classify a raw {@code type}/{@code group_type} string from the
     * operation payload.
     *
     * @param rawValue caller-supplied string (may be null/blank)
     * @return verdict — see {@link Verdict}
     */
    public static Verdict classify(String rawValue) {
        if (rawValue == null) {
            return Verdict.UNSPECIFIED;
        }
        String normalized = normalize(rawValue);
        if (normalized.isEmpty()) {
            return Verdict.UNSPECIFIED;
        }
        if (TABLE_TOKENS.contains(normalized)) {
            return Verdict.TABLE_NOT_A_GROUP;
        }
        if (KNOWN_GROUP_TYPE_TOKENS.contains(normalized)) {
            return Verdict.RECOGNIZED_GROUP_TYPE;
        }
        return Verdict.UNRECOGNIZED;
    }

    /**
     * Resolve the raw type-string from an {@code add_group} operation,
     * looking at all three commonly-used positions in priority order:
     * {@code group_type} (most specific), top-level {@code type},
     * {@code set.type} (legacy nested form).  Case-insensitive.  Returns
     * null when none is set.
     */
    public static String extractRawType(Map<String, Object> operation, Map<String, Object> set) {
        String value = caseInsensitiveString(operation, "group_type"); //$NON-NLS-1$
        if (value != null) {
            return value;
        }
        value = caseInsensitiveString(operation, "type"); //$NON-NLS-1$
        if (value != null) {
            return value;
        }
        return caseInsensitiveString(set, "type"); //$NON-NLS-1$
    }

    /**
     * Build the agent-facing rejection message for the
     * {@link Verdict#TABLE_NOT_A_GROUP} case.  Keeping the wording in one
     * place makes it easy to assert on in tests and to keep agent-facing
     * language consistent.
     *
     * @param rawValue the original {@code type} string (echoed back verbatim)
     * @param groupName the {@code name} of the requested group, may be null
     * @return human-readable message
     */
    public static String tableNotAGroupMessage(String rawValue, String groupName) {
        StringBuilder sb = new StringBuilder();
        sb.append("add_group type='").append(rawValue).append("' is not supported"); //$NON-NLS-1$ //$NON-NLS-2$
        if (groupName != null && !groupName.isBlank()) {
            sb.append(" (name='").append(groupName).append("')"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(": Table is a distinct form element type (xsi:type=\"form:Table\")," //$NON-NLS-1$
                + " not a FormGroup variant — emitting it from add_group" //$NON-NLS-1$
                + " would silently downgrade to UsualGroup. Until mutate_form_model" //$NON-NLS-1$
                + " grows a dedicated add_table op, edit the Form.form XML directly" //$NON-NLS-1$
                + " (Edit/Write tools) using the Table block from a sibling form as" //$NON-NLS-1$
                + " a template, then run inspect_form_layout to confirm" //$NON-NLS-1$
                + " kind=\"Table\"."); //$NON-NLS-1$
        return sb.toString();
    }

    private static String normalize(String value) {
        return value
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private static String caseInsensitiveString(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty() || key == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                String s = String.valueOf(entry.getValue()).trim();
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }
}
