package com.codepilot1c.core.edt.forms;

import java.util.List;

/**
 * Result of managed form model mutation.
 */
public record UpdateFormModelResult(
        String projectName,
        String formFqn,
        int operationsApplied,
        List<String> operationSummaries
) {
    public String formatForLlm() {
        StringBuilder details = new StringBuilder();
        if (operationSummaries != null) {
            for (String operationSummary : operationSummaries) {
                if (operationSummary == null || operationSummary.isBlank()) {
                    continue;
                }
                details.append("- ").append(operationSummary).append('\n'); //$NON-NLS-1$
            }
        }
        return """
                ✅ Модель формы обновлена.
                Проект: %s
                Форма: %s
                Применено операций: %d
                %s
                """.formatted(
                safe(projectName),
                safe(formFqn),
                Integer.valueOf(operationsApplied),
                details.toString());
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
