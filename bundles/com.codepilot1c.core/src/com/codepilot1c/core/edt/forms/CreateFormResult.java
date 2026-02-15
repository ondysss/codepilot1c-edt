package com.codepilot1c.core.edt.forms;

import com.codepilot1c.core.edt.metadata.MetadataOperationResult;

/**
 * Result of managed form creation.
 */
public record CreateFormResult(
        String ownerFqn,
        String formFqn,
        FormUsage usage,
        boolean defaultAssigned,
        boolean materialized,
        String formFilePath,
        String moduleFilePath,
        String diagnostics
) {
    public MetadataOperationResult toMetadataOperationResult(String projectName, String name) {
        return new MetadataOperationResult(
                true,
                projectName,
                "FORM", //$NON-NLS-1$
                name,
                formFqn,
                summaryMessage());
    }

    public String formatForLlm(String projectName, String name) {
        return """
                ✅ Форма успешно создана.
                Проект: %s
                Владелец: %s
                Имя: %s
                FQN: %s
                Роль: %s
                Назначена как default: %s
                Материализация: %s
                Путь данных формы: %s
                Module.bsl: %s
                %s
                """.formatted(
                safe(projectName),
                safe(ownerFqn),
                safe(name),
                safe(formFqn),
                usage == null ? "" : usage.name(), //$NON-NLS-1$
                Boolean.toString(defaultAssigned),
                Boolean.toString(materialized),
                safe(formFilePath),
                modulePathForDisplay(),
                safe(diagnostics)); //$NON-NLS-1$
    }

    private String summaryMessage() {
        return "Form role=" + (usage == null ? "AUXILIARY" : usage.name()) //$NON-NLS-1$ //$NON-NLS-2$
                + ", defaultAssigned=" + defaultAssigned //$NON-NLS-1$
                + ", materialized=" + materialized //$NON-NLS-1$
                + ", formFilePath=" + safe(formFilePath) //$NON-NLS-1$
                + ", moduleFilePath=" + safe(moduleFilePath) //$NON-NLS-1$
                + (diagnostics == null || diagnostics.isBlank() ? "" : ", diagnostics=" + diagnostics); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private String modulePathForDisplay() {
        if (moduleFilePath == null || moduleFilePath.isBlank()) {
            return "(не используется в текущем EDT-формате)"; //$NON-NLS-1$
        }
        return moduleFilePath;
    }
}
