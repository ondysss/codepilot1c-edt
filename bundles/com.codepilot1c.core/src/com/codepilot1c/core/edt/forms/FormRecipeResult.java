/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.forms;

import java.util.List;

/**
 * Result of apply_form_recipe execution.
 */
public record FormRecipeResult(
        String projectName,
        String formFqn,
        int attributesCreated,
        int attributesUpdated,
        int attributesRemoved,
        int layoutOperationsApplied,
        List<String> layoutOperationSummaries
) {
    public String formatForLlm() {
        StringBuilder summaries = new StringBuilder();
        if (layoutOperationSummaries != null) {
            for (String summary : layoutOperationSummaries) {
                if (summary == null || summary.isBlank()) {
                    continue;
                }
                summaries.append("- ").append(summary).append('\n'); //$NON-NLS-1$
            }
        }
        return """
                ✅ Рецепт формы применен.
                Проект: %s
                Форма: %s
                Создано реквизитов формы: %d
                Обновлено реквизитов формы: %d
                Удалено реквизитов формы: %d
                Операций по макету: %d
                %s
                Рекомендуется проверить get_diagnostics (scope=file и scope=project).
                """.formatted(
                safe(projectName),
                safe(formFqn),
                Integer.valueOf(attributesCreated),
                Integer.valueOf(attributesUpdated),
                Integer.valueOf(attributesRemoved),
                Integer.valueOf(layoutOperationsApplied),
                summaries.toString());
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
