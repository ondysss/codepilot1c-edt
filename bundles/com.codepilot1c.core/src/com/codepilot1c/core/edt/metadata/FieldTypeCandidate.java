package com.codepilot1c.core.edt.metadata;

/**
 * Single EDT type candidate for a metadata field.
 */
public record FieldTypeCandidate(
        String name,
        String nameRu,
        String code,
        String codeRu,
        String typeClass,
        boolean simpleType
) {
}
