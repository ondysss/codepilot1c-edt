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
        String normalized = normalizeToken(value);
        return switch (normalized) {
            case "catalog", "catalogs", "справочник", "справочники" -> CATALOG; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "document", "documents", "документ", "документы" -> DOCUMENT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "informationregister", "informationregisters", "информационныйрегистр", "регистрсведений", "регистрысведений" -> INFORMATION_REGISTER; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            case "accumulationregister", "accumulationregisters", "регистрнакопления", "регистрынакопления" -> ACCUMULATION_REGISTER; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "commonmodule", "commonmodules", "общиймодуль", "общиемодули" -> COMMON_MODULE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "enum", "enums", "перечисление", "перечисления" -> ENUM; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "report", "reports", "отчет", "отчеты" -> REPORT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "dataprocessor", "dataprocessors", "обработка", "обработки" -> DATA_PROCESSOR; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "constant", "constants", "константа", "константы" -> CONSTANT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported metadata kind: " + value, false); //$NON-NLS-1$
        };
    }

    private static String normalizeToken(String value) {
        String lowered = value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder sb = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char ch = lowered.charAt(i);
            if (ch == '_' || ch == '-' || Character.isWhitespace(ch) || ch == '.') {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }
}
