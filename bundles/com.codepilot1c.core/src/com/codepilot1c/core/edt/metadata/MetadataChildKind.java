package com.codepilot1c.core.edt.metadata;

import java.util.Locale;

/**
 * Supported child metadata kinds.
 */
public enum MetadataChildKind {
    ATTRIBUTE("Attribute"),
    TABULAR_SECTION("TabularSection"),
    COMMAND("Command"),
    FORM("Form"),
    TEMPLATE("Template"),
    DIMENSION("Dimension"),
    RESOURCE("Resource"),
    REQUISITE("Requisite"),
    ENUM_VALUE("EnumValue");

    private final String displayName;

    MetadataChildKind(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static MetadataChildKind fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Child metadata kind is required", false); //$NON-NLS-1$
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "attribute", "атрибут", "реквизит", "tabular_section_attribute", "tabularsectionattribute" -> ATTRIBUTE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            case "tabular_section", "document_tabular_section", "tabularsection", "табличнаячасть" -> TABULAR_SECTION; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "command", "команда" -> COMMAND; //$NON-NLS-1$ //$NON-NLS-2$
            case "form", "форма" -> FORM; //$NON-NLS-1$ //$NON-NLS-2$
            case "template", "макет" -> TEMPLATE; //$NON-NLS-1$ //$NON-NLS-2$
            case "dimension", "измерение" -> DIMENSION; //$NON-NLS-1$ //$NON-NLS-2$
            case "resource", "ресурс" -> RESOURCE; //$NON-NLS-1$ //$NON-NLS-2$
            case "requisite", "реквизитрегистра" -> REQUISITE; //$NON-NLS-1$ //$NON-NLS-2$
            case "enum_value", "enumvalue", "значениеперечисления" -> ENUM_VALUE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported child metadata kind: " + value, false); //$NON-NLS-1$
        };
    }
}
