/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * Инструмент записи в существующие файлы.
 *
 * <p>Используется только для изменения существующих файлов в workspace.
 * Создание новых файлов через этот инструмент запрещено.</p>
 *
 * <p>Особенности:</p>
 * <ul>
 *   <li>Не создает новые файлы и директории</li>
 *   <li>Поддерживает UTF-8 кодировку</li>
 *   <li>Работает только в пределах workspace (безопасность)</li>
 *   <li>Перезаписывает существующие файлы (с overwrite=true)</li>
 * </ul>
 */
public class WriteTool implements ITool {

    private static final String PLUGIN_ID = "com.codepilot1c.core";
    private static final ILog LOG = Platform.getLog(WriteTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path to an existing file (workspace-relative)"
                    },
                    "content": {
                        "type": "string",
                        "description": "Content to write to the file"
                    },
                    "overwrite": {
                        "type": "boolean",
                        "description": "Overwrite if file exists (default: false)"
                    }
                },
                "required": ["path", "content"]
            }
            """;

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "Записывает содержимое в существующий файл. Путь должен быть " +
               "относительным к workspace (например, 'project/src/Module.bsl'). " +
               "Создание новых файлов запрещено: сначала создайте объект метаданных через create_metadata/add_metadata_child, " +
               "после чего редактируйте уже созданные модульные файлы. " +
               "⚠️ ЗАПРЕЩЕНО использовать для создания объектов метаданных 1С (.mdo файлов)! " +
               "Для справочников, документов, регистров и др. ОБЯЗАТЕЛЬНО используйте " +
               "инструменты create_metadata (top-level) и add_metadata_child (вложенные объекты).";
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;  // Агент может создавать файлы без подтверждения
    }

    @Override
    public boolean isDestructive() {
        return false;  // Не требует специальной обработки
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String pathStr = (String) parameters.get("path");
            if (pathStr == null || pathStr.isEmpty()) {
                return ToolResult.failure("Параметр path обязателен");
            }

            String content = (String) parameters.get("content");
            if (content == null) {
                content = "";
            }

            boolean overwrite = Boolean.TRUE.equals(parameters.get("overwrite"));

            if (!overwrite) {
                return ToolResult.failure(
                        "write_file разрешен только для перезаписи существующих файлов. " +
                        "Укажите overwrite=true.");
            }

            try {
                return writeFile(pathStr, content);
            } catch (CoreException e) {
                logError("Ошибка создания файла", e);
                return ToolResult.failure("Ошибка записи файла: " + e.getMessage());
            }
        });
    }

    /**
     * Записывает содержимое в существующий файл.
     */
    private ToolResult writeFile(String pathStr, String content)
            throws CoreException {

        // Normalize path
        String normalizedPath = normalizePath(pathStr);

        // КРИТИЧНО: Любое прямое редактирование metadata descriptor (.mdo) запрещено.
        if (isMetadataDescriptorPath(normalizedPath)) {
            logWarning("═══════════════════════════════════════════════════════════════");
            logWarning("[WRITE_FILE] ✗ ЗАБЛОКИРОВАНО: Попытка редактировать .mdo файл напрямую!");
            logWarning("[WRITE_FILE] Путь: " + normalizedPath);
            logWarning("[WRITE_FILE] Размер контента: " + (content != null ? content.length() : 0) + " символов");
            logWarning("[WRITE_FILE] РЕШЕНИЕ: Используйте create_metadata/add_metadata_child для изменения метаданных");
            logWarning("═══════════════════════════════════════════════════════════════");
            return ToolResult.failure(
                    "❌ ОШИБКА: Нельзя редактировать .mdo файлы метаданных напрямую через write_file!\n\n" +
                    "Используйте инструмент **create_metadata** для создания top-level объектов метаданных.\n" +
                    "Для табличных частей и реквизитов табличных частей используйте **add_metadata_child**.\n" +
                    "Это необходимо, чтобы изменения проходили через штатный BM API EDT.\n\n" +
                    "Пример: create_metadata(kind=\"Catalog\", name=\"Контрагенты\", synonym=\"Контрагенты\")");
        }

        // Get workspace
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Find file handle
        IFile file = findOrCreateFile(root, normalizedPath);
        if (file == null) {
            return ToolResult.failure("Не удалось получить файл: " + pathStr);
        }

        if (!file.exists()) {
            return ToolResult.failure(
                    "Создание новых файлов через write_file запрещено: " + file.getFullPath() + ". " +
                    "Используйте create_metadata/add_metadata_child для метаданных и edit_file для редактирования существующих модулей.");
        }

        // Write content
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);

        file.setContents(source, IResource.FORCE, new NullProgressMonitor());

        // Refresh
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        // Build result
        StringBuilder result = new StringBuilder();
        result.append("**Файл обновлен:** `").append(file.getFullPath()).append("`\n");
        result.append("**Размер:** ").append(bytes.length).append(" байт\n");
        result.append("**Строк:** ").append(countLines(content)).append("\n");
        result.append("**Статус:** перезаписан");

        logInfo("Файл обновлен: " + file.getFullPath());

        return ToolResult.success(result.toString(), ToolResult.ToolResultType.TEXT);
    }

    /**
     * Нормализует путь.
     */
    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }

        // Remove leading slash
        String normalized = path;
        if (normalized.startsWith("/") && !normalized.startsWith("//")) {
            normalized = normalized.substring(1);
        }

        // Convert separators
        normalized = normalized.replace('\\', '/');

        return normalized;
    }

    /**
     * Находит или создает файл по пути.
     */
    private IFile findOrCreateFile(IWorkspaceRoot root, String path) {
        try {
            // Try to get file handle
            IPath ipath = org.eclipse.core.runtime.Path.fromPortableString(path);
            return root.getFile(ipath);
        } catch (Exception e) {
            logError("Ошибка получения файла: " + path, e);
            return null;
        }
    }

    /**
     * Подсчитывает количество строк.
     */
    private int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return content.split("\r\n|\r|\n", -1).length;
    }

    private boolean isMetadataDescriptorPath(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mdo"); //$NON-NLS-1$
    }

    private void logInfo(String message) {
        LOG.log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    private void logWarning(String message) {
        LOG.log(new Status(IStatus.WARNING, PLUGIN_ID, message));
    }

    private void logError(String message, Throwable error) {
        LOG.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }
}
