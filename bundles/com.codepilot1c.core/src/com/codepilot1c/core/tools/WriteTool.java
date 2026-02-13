/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
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
 * Инструмент создания новых файлов.
 *
 * <p>Создает новые файлы в workspace. Для редактирования существующих
 * файлов используйте edit_file.</p>
 *
 * <p>Особенности:</p>
 * <ul>
 *   <li>Создает родительские директории автоматически</li>
 *   <li>Поддерживает UTF-8 кодировку</li>
 *   <li>Работает только в пределах workspace (безопасность)</li>
 *   <li>Может перезаписывать существующие файлы (с подтверждением)</li>
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
                        "description": "Path for the new file (workspace-relative)"
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
        return "Создает новый файл с указанным содержимым. Путь должен быть " +
               "относительным к workspace (например, 'project/src/Module.bsl'). " +
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

            try {
                return writeFile(pathStr, content, overwrite);
            } catch (CoreException e) {
                logError("Ошибка создания файла", e);
                return ToolResult.failure("Ошибка создания файла: " + e.getMessage());
            }
        });
    }

    /**
     * Создает файл с указанным содержимым.
     */
    private ToolResult writeFile(String pathStr, String content, boolean overwrite)
            throws CoreException {

        // Normalize path
        String normalizedPath = normalizePath(pathStr);

        // КРИТИЧНО: Проверяем попытку создать MDO файл напрямую
        if (normalizedPath != null && normalizedPath.endsWith(".mdo") //$NON-NLS-1$
                && !normalizedPath.contains("Configuration.mdo")) { //$NON-NLS-1$
            logWarning("═══════════════════════════════════════════════════════════════");
            logWarning("[WRITE_FILE] ✗ ЗАБЛОКИРОВАНО: Попытка создать .mdo файл напрямую!");
            logWarning("[WRITE_FILE] Путь: " + normalizedPath);
            logWarning("[WRITE_FILE] Размер контента: " + (content != null ? content.length() : 0) + " символов");
            logWarning("[WRITE_FILE] РЕШЕНИЕ: Используйте create_metadata для создания объектов метаданных");
            logWarning("═══════════════════════════════════════════════════════════════");
            return ToolResult.failure(
                    "❌ ОШИБКА: Нельзя создавать .mdo файлы метаданных напрямую через write_file!\n\n" +
                    "Используйте инструмент **create_metadata** для создания объектов метаданных.\n" +
                    "Для табличных частей и реквизитов табличных частей используйте **add_metadata_child**.\n" +
                    "Это необходимо, чтобы объект был автоматически добавлен в Configuration.mdo.\n\n" +
                    "Пример: create_metadata(kind=\"Catalog\", name=\"Контрагенты\", synonym=\"Контрагенты\")");
        }

        // Get workspace
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Find or create file
        IFile file = findOrCreateFile(root, normalizedPath);
        if (file == null) {
            return ToolResult.failure("Не удалось создать файл: " + pathStr);
        }

        // Check if file exists
        if (file.exists() && !overwrite) {
            return ToolResult.failure(
                    "Файл уже существует: " + file.getFullPath() +
                    ". Используйте overwrite=true для перезаписи или edit_file для редактирования.");
        }

        // Ensure parent folders exist
        ensureParentExists(file);

        // Write content
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);

        if (file.exists()) {
            file.setContents(source, IResource.FORCE, new NullProgressMonitor());
        } else {
            file.create(source, IResource.FORCE, new NullProgressMonitor());
        }

        // Refresh
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        // Build result
        StringBuilder result = new StringBuilder();
        result.append("**Файл создан:** `").append(file.getFullPath()).append("`\n");
        result.append("**Размер:** ").append(bytes.length).append(" байт\n");
        result.append("**Строк:** ").append(countLines(content)).append("\n");

        if (file.exists()) {
            result.append("**Статус:** ").append(overwrite ? "перезаписан" : "создан");
        }

        logInfo("Файл создан: " + file.getFullPath());

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
     * Создает родительские директории если нужно.
     */
    private void ensureParentExists(IFile file) throws CoreException {
        IResource parent = file.getParent();

        if (parent instanceof IFolder) {
            ensureFolderExists((IFolder) parent);
        } else if (parent instanceof IProject) {
            IProject project = (IProject) parent;
            if (!project.exists()) {
                project.create(new NullProgressMonitor());
                project.open(new NullProgressMonitor());
            }
        }
    }

    /**
     * Рекурсивно создает папки.
     */
    private void ensureFolderExists(IFolder folder) throws CoreException {
        if (folder.exists()) {
            return;
        }

        // First ensure parent exists
        IResource parent = folder.getParent();
        if (parent instanceof IFolder) {
            ensureFolderExists((IFolder) parent);
        } else if (parent instanceof IProject) {
            IProject project = (IProject) parent;
            if (!project.exists()) {
                project.create(new NullProgressMonitor());
                project.open(new NullProgressMonitor());
            }
        }

        // Create this folder
        folder.create(IResource.FORCE, true, new NullProgressMonitor());
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
