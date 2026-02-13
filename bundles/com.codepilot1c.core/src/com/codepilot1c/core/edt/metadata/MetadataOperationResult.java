package com.codepilot1c.core.edt.metadata;

/**
 * Generic result for metadata operations.
 */
public record MetadataOperationResult(
        boolean success,
        String projectName,
        String kind,
        String name,
        String fqn,
        String message
) {
    public String formatForLlm() {
        if (success) {
            return """
                    ✅ Операция с метаданными выполнена.
                    Проект: %s
                    Тип: %s
                    Имя: %s
                    FQN: %s
                    %s
                    """.formatted(
                    safe(projectName),
                    safe(kind),
                    safe(name),
                    safe(fqn),
                    safe(message)); //$NON-NLS-1$
        }
        return """
                ❌ Операция с метаданными не выполнена.
                Проект: %s
                Тип: %s
                Имя: %s
                %s
                """.formatted(
                safe(projectName),
                safe(kind),
                safe(name),
                safe(message)); //$NON-NLS-1$
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
