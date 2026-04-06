/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

import java.util.List;
import java.util.Map;

/**
 * Result of inspecting an existing template.
 */
public record InspectTemplateResult(
        String projectName,
        String templateFqn,
        String templateType,
        String mxlPath,
        int totalRows,
        int totalColumns,
        List<Map<String, Object>> namedAreas,
        List<List<String>> grid
) {

    public String formatForLlm() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Макет: ").append(safe(templateFqn)).append('\n'); //$NON-NLS-1$
        sb.append("Тип: ").append(safe(templateType)).append('\n'); //$NON-NLS-1$
        sb.append("Файл: ").append(safe(mxlPath)).append('\n'); //$NON-NLS-1$
        sb.append("Размер: ").append(totalRows).append(" строк x ").append(totalColumns).append(" колонок\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if (namedAreas != null && !namedAreas.isEmpty()) {
            sb.append("\nИменованные области:\n"); //$NON-NLS-1$
            for (Map<String, Object> area : namedAreas) {
                sb.append("  - ").append(area.getOrDefault("name", "?")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Object begin = area.get("begin"); //$NON-NLS-1$
                Object end = area.get("end"); //$NON-NLS-1$
                if (begin != null && end != null) {
                    sb.append(" (строки ").append(begin).append("-").append(end).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                sb.append('\n');
            }
        }

        if (grid != null && !grid.isEmpty()) {
            sb.append("\nСодержимое (text|[parameter]):\n"); //$NON-NLS-1$
            int rowIdx = 0;
            for (List<String> row : grid) {
                rowIdx++;
                sb.append(String.format("  %3d: ", rowIdx)); //$NON-NLS-1$
                sb.append(String.join(" | ", row)); //$NON-NLS-1$
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
