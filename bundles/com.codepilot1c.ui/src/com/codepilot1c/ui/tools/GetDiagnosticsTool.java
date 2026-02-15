/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.ui.diagnostics.EdtDiagnostic.Severity;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector.DiagnosticsQuery;
import com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector.DiagnosticsResult;

/**
 * Tool for getting EDT diagnostics (errors, warnings) for project, file or active editor.
 *
 * <p>This tool allows the LLM to retrieve compiler/checker diagnostics from 1C EDT,
 * enabling intelligent auto-fix workflows.</p>
 *
 * <p>Example usage by LLM:</p>
 * <ul>
 * <li>"get_diagnostics" → get errors from project</li>
 * <li>"get_diagnostics(severity='warning')" → get errors and warnings</li>
 * <li>"get_diagnostics(path='/Project/Module.bsl')" → get diagnostics for specific file</li>
 * </ul>
 */
public class GetDiagnosticsTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetDiagnosticsTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "scope": {
                        "type": "string",
                        "enum": ["project", "file", "active_editor"],
                        "description": "Область диагностики: project (по project_name или по всем проектам), file (по path), active_editor (по активному редактору)"
                    },
                    "path": {
                        "type": "string",
                        "description": "Путь к файлу (workspace-relative) для scope=file"
                    },
                    "project_name": {
                        "type": "string",
                        "description": "Имя проекта EDT для scope=project. Если не указан, будет использован default project или диагностика по workspace"
                    },
                    "severity": {
                        "type": "string",
                        "enum": ["error", "warning", "info"],
                        "description": "Минимальный уровень серьёзности: error (только ошибки), warning (ошибки и предупреждения), info (все). По умолчанию: error"
                    },
                    "max_items": {
                        "type": "integer",
                        "description": "Максимальное количество диагностик. По умолчанию: 50"
                    },
                    "wait_ms": {
                        "type": "integer",
                        "description": "Время ожидания пересчёта диагностик в мс (0-2000). По умолчанию: 0"
                    },
                    "include_runtime_markers": {
                        "type": "boolean",
                        "description": "Включить дополнительные диагностики из EDT marker manager (для scope=project). По умолчанию: true"
                    }
                },
                "required": []
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "get_diagnostics"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Получить диагностики EDT (ошибки компиляции, предупреждения) по проекту/файлу. " + //$NON-NLS-1$
               "По умолчанию анализирует проект и не требует активного редактора."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        // Parse parameters
        String scope = (String) parameters.getOrDefault("scope", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String path = (String) parameters.get("path"); //$NON-NLS-1$
        String projectName = (String) parameters.get("project_name"); //$NON-NLS-1$
        String severityStr = (String) parameters.getOrDefault("severity", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        int maxItems = getIntParam(parameters, "max_items", 50); //$NON-NLS-1$
        long waitMs = getIntParam(parameters, "wait_ms", 0); //$NON-NLS-1$
        boolean includeRuntimeMarkers = getBooleanParam(parameters, "include_runtime_markers", true); //$NON-NLS-1$

        // Validate parameters
        if (maxItems < 1) maxItems = 1;
        if (maxItems > 200) maxItems = 200;
        if (waitMs < 0) waitMs = 0;
        if (waitMs > 2000) waitMs = 2000;

        // Parse severity
        Severity minSeverity = parseSeverity(severityStr);

        DiagnosticsQuery query = new DiagnosticsQuery(minSeverity, maxItems, true, waitMs, includeRuntimeMarkers);
        EdtDiagnosticsCollector collector = EdtDiagnosticsCollector.getInstance();

        String normalizedScope = normalizeScope(scope, path, projectName);
        boolean collectWorkspaceDiagnostics = false;
        if ("project".equals(normalizedScope) && (projectName == null || projectName.isBlank())) { //$NON-NLS-1$
            projectName = collector.resolveDefaultProjectName();
            if (projectName == null || projectName.isBlank()) {
                collectWorkspaceDiagnostics = true;
            }
        }
        LOG.debug("get_diagnostics: scope=%s, project=%s, path=%s, severity=%s, max=%d, wait=%d, runtime=%s", //$NON-NLS-1$
                normalizedScope, projectName, path, minSeverity, maxItems, waitMs, includeRuntimeMarkers);

        CompletableFuture<DiagnosticsResult> resultFuture;

        resultFuture = switch (normalizedScope) {
            case "project" -> collectWorkspaceDiagnostics
                    ? collector.collectFromWorkspace(query)
                    : collector.collectFromProject(projectName, query); //$NON-NLS-1$
            case "file" -> collector.collectFromFile(path, query); //$NON-NLS-1$
            default -> collector.collectFromActiveEditor(query);
        };

        return resultFuture.thenApply(result -> {
            String formatted = result.formatForLlm();
            LOG.debug("get_diagnostics result: %d errors, %d warnings", //$NON-NLS-1$
                    result.errorCount(), result.warningCount());
            return ToolResult.success(formatted);
        }).exceptionally(e -> {
            LOG.error("get_diagnostics failed: %s", e.getMessage()); //$NON-NLS-1$
            return ToolResult.failure("Ошибка получения диагностик: " + e.getMessage()); //$NON-NLS-1$
        });
    }

    private Severity parseSeverity(String str) {
        if (str == null) return Severity.ERROR;
        return switch (str.toLowerCase()) {
            case "warning", "warn" -> Severity.WARNING; //$NON-NLS-1$ //$NON-NLS-2$
            case "info", "all" -> Severity.INFO; //$NON-NLS-1$ //$NON-NLS-2$
            default -> Severity.ERROR;
        };
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String str = String.valueOf(value).trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(str);
    }

    private String normalizeScope(String scope, String path, String projectName) {
        if (scope != null && !scope.isBlank()) {
            String normalized = scope.trim().toLowerCase();
            if ("project".equals(normalized) || "file".equals(normalized) || "active_editor".equals(normalized)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if ("file".equals(normalized) && (path == null || path.isBlank())) { //$NON-NLS-1$
                    return "project"; //$NON-NLS-1$
                }
                return normalized;
            }
        }
        if (path != null && !path.isBlank()) {
            return "file"; //$NON-NLS-1$
        }
        return "project"; //$NON-NLS-1$
    }
}
