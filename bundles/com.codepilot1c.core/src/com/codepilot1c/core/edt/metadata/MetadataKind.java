package com.codepilot1c.core.edt.metadata;

import java.util.Locale;

/**
 * Supported top-level metadata kinds.
 */
public enum MetadataKind {
    CATALOG("Catalog", "Справочник"),
    DOCUMENT("Document", "Документ"),
    INFORMATION_REGISTER("InformationRegister", "РегистрСведений"),
    ACCUMULATION_REGISTER("AccumulationRegister", "РегистрНакопления"),
    COMMON_MODULE("CommonModule", "ОбщийМодуль"),
    ENUM("Enum", "Перечисление"),
    REPORT("Report", "Отчет"),
    DATA_PROCESSOR("DataProcessor", "Обработка"),
    CONSTANT("Constant", "Константа");

    private final String fqnPrefix;
    private final String ruName;

    MetadataKind(String fqnPrefix, String ruName) {
        this.fqnPrefix = fqnPrefix;
        this.ruName = ruName;
    }

    public String getFqnPrefix() {
        return fqnPrefix;
    }

    public String getRuName() {
        return ruName;
    }

    public static MetadataKind fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Metadata kind is required", false); //$NON-NLS-1$
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "catalog", "справочник", "catalogs" -> CATALOG; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "document", "документ", "documents" -> DOCUMENT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "informationregister", "информационныйрегистр", "регистрсведений" -> INFORMATION_REGISTER; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "accumulationregister", "регистрнакопления" -> ACCUMULATION_REGISTER; //$NON-NLS-1$ //$NON-NLS-2$
            case "commonmodule", "общиймодуль" -> COMMON_MODULE; //$NON-NLS-1$ //$NON-NLS-2$
            case "enum", "перечисление" -> ENUM; //$NON-NLS-1$ //$NON-NLS-2$
            case "report", "отчет", "отчёт" -> REPORT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dataprocessor", "обработка" -> DATA_PROCESSOR; //$NON-NLS-1$ //$NON-NLS-2$
            case "constant", "константа" -> CONSTANT; //$NON-NLS-1$ //$NON-NLS-2$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported metadata kind: " + value, false); //$NON-NLS-1$
        };
    }
}
