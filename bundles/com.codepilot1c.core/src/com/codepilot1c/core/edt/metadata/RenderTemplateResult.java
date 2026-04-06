/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

import java.util.List;

/**
 * Result of rendering a template layout.
 */
public record RenderTemplateResult(
        String projectName,
        String templateFqn,
        String mxlPath,
        int totalRows,
        int totalColumns,
        List<String> sectionSummaries,
        List<String> warnings
) {

    public String formatForLlm() {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ Макет отрисован.\n"); //$NON-NLS-1$
        sb.append("Проект: ").append(safe(projectName)).append('\n'); //$NON-NLS-1$
        sb.append("Макет: ").append(safe(templateFqn)).append('\n'); //$NON-NLS-1$
        sb.append("Файл: ").append(safe(mxlPath)).append('\n'); //$NON-NLS-1$
        sb.append("Строк: ").append(totalRows).append(", Колонок: ").append(totalColumns).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (sectionSummaries != null && !sectionSummaries.isEmpty()) {
            sb.append("Секции:\n"); //$NON-NLS-1$
            for (String s : sectionSummaries) {
                sb.append("  - ").append(s).append('\n'); //$NON-NLS-1$
            }
        }
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("Предупреждения:\n"); //$NON-NLS-1$
            for (String w : warnings) {
                sb.append("  ⚠ ").append(w).append('\n'); //$NON-NLS-1$
            }
        }
        return sb.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
