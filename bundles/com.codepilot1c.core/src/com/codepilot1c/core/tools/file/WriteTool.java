/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.edt.ast.BmSyncHelper;
import com.codepilot1c.core.agent.profiles.GsdShipPathPolicy;
import com.codepilot1c.core.filesystem.SecureDirectoryMutation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;


/**
 * Инструмент записи файлов workspace.
 *
 * <p>Используется для изменения существующих файлов в workspace.
 * Дополнительно разрешает создать Code.md в корне текущего проекта и новые
 * документационные файлы (*.md, *.txt) внутри проекта.</p>
 *
 * <p>Особенности:</p>
 * <ul>
 *   <li>Из новых файлов создает только Code.md в корне проекта и документацию (*.md, *.txt),
 *       при необходимости создавая промежуточные папки</li>
 *   <li>Структурные EDT-артефакты (.mdo/.form/.mxl/DCS) запрещены — для них есть семантические инструменты</li>
 *   <li>Поддерживает UTF-8 кодировку</li>
 *   <li>Работает только в пределах workspace (безопасность)</li>
 *   <li>Перезаписывает существующие файлы (с overwrite=true)</li>
 * </ul>
 */
@ToolMeta(name = "write_file", category = "file", mutating = true, tags = {"workspace"})
public class WriteTool extends AbstractTool {

    private static final String PLUGIN_ID = "com.codepilot1c.core";
    private final SecureDirectoryMutation.MutationHook shipMutationHook;

    public WriteTool() {
        this(null);
    }

    WriteTool(SecureDirectoryMutation.MutationHook shipMutationHook) {
        this.shipMutationHook = shipMutationHook;
    }

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path to a workspace file. Existing files are overwritten; new files may be created only for project-root Code.md or documentation (*.md, *.txt)."
                    },
                    "content": {
                        "type": "string",
                        "description": "Full new file content"
                    },
                    "overwrite": {
                        "type": "boolean",
                        "description": "Must be true; existing files are overwritten, and new Code.md/documentation (*.md, *.txt) may be created"
                    },
                    "allow_empty": {
                        "type": "boolean",
                        "description": "Must be true to write empty content over an existing non-empty file"
                    }
                },
                "required": ["path", "content", "overwrite"]
            }
            """;

    @Override
    public String getDescription() {
        return "Перезаписывает файл workspace целиком; может создать Code.md в корне проекта и новые документационные файлы (*.md, *.txt). Используй для осознанного full overwrite или сохранения заметок/документации. Предпочитай edit_file для точечных правок; не используй для EDT metadata или .mdo/.form/.mxl файлов."; //$NON-NLS-1$
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
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return doExecute(params, ToolExecutionContext.unscoped());
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(
            ToolParameters params, ToolExecutionContext context) {
        Map<String, Object> parameters = params.getRaw();
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
            boolean allowEmpty = Boolean.TRUE.equals(parameters.get("allow_empty"));

            if (!overwrite) {
                return ToolResult.failure(
                        "write_file разрешен только для перезаписи существующих файлов. " +
                        "Укажите overwrite=true.");
            }

            try {
                return writeFile(pathStr, content, allowEmpty, context);
            } catch (CoreException e) {
                logError("Ошибка создания файла", e);
                return ToolResult.failure("Ошибка записи файла: " + e.getMessage());
            }
        });
    }

    /**
     * Записывает содержимое в файл workspace.
     */
    private ToolResult writeFile(
            String pathStr,
            String content,
            boolean allowEmpty,
            ToolExecutionContext context)
            throws CoreException {

        boolean shipScoped = context != null
                && "gsd-ship".equals(context.parentProfileId()); //$NON-NLS-1$
        if (shipScoped && !GsdShipPathPolicy.isReleaseArtifactPath(pathStr)) {
            return ToolResult.failure(
                    "GSD Ship may write only canonical release-artifact paths"); //$NON-NLS-1$
        }

        // Normalize path
        String normalizedPath = normalizePath(pathStr);

        // КРИТИЧНО: Любое прямое редактирование metadata descriptor (.mdo) запрещено.
        if (isMetadataDescriptorPath(normalizedPath)) {
            logWarning("═══════════════════════════════════════════════════════════════");
            logWarning("[WRITE_FILE] ✗ ЗАБЛОКИРОВАНО: Попытка редактировать .mdo файл напрямую!");
            logWarning("[WRITE_FILE] Путь: " + normalizedPath);
            logWarning("[WRITE_FILE] Размер контента: " + (content != null ? content.length() : 0) + " символов");
            logWarning("[WRITE_FILE] РЕШЕНИЕ: Используйте create_metadata/create_form/add_metadata_child для изменения метаданных");
            logWarning("═══════════════════════════════════════════════════════════════");
            return ToolResult.failure(
                    "❌ ОШИБКА: Нельзя редактировать .mdo файлы метаданных напрямую через write_file!\n\n" +
                    "Используйте инструмент **create_metadata** для создания top-level объектов метаданных.\n" +
                    "Для форм используйте **create_form**.\n" +
                    "Для табличных частей и реквизитов табличных частей используйте **add_metadata_child**.\n" +
                    "Это необходимо, чтобы изменения проходили через штатный BM API EDT.\n\n" +
                    "Пример: create_metadata(kind=\"Catalog\", name=\"Контрагенты\", synonym=\"Контрагенты\")");
        }

        // FORM/DCS/TEMPLATE artifacts are structured EDT files and must be changed through semantic tools.
        if (isStructuredEdtArtifactPath(normalizedPath)) {
            logWarning("═══════════════════════════════════════════════════════════════");
            logWarning("[WRITE_FILE] ✗ ЗАБЛОКИРОВАНО: Попытка записать структурный EDT artifact напрямую!");
            logWarning("[WRITE_FILE] Путь: " + normalizedPath);
            logWarning("[WRITE_FILE] Размер контента: " + (content != null ? content.length() : 0) + " символов");
            logWarning("[WRITE_FILE] РЕШЕНИЕ: Используйте create_form/mutate_form_model, dcs_manage или render_template");
            logWarning("═══════════════════════════════════════════════════════════════");
            return ToolResult.failure(
                    "Writing structured EDT artifacts is blocked. Use semantic EDT tools instead.\n\n" +
                    "Для форм используйте create_form/apply_form_recipe/mutate_form_model.\n" +
                    "Для СКД используйте dcs_manage.\n" +
                    "Для .mxl макетов используйте add_metadata_child(child_kind=Template) и render_template.");
        }

        // Get workspace
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Find file handle
        IProject currentProject = resolveCurrentProject(context);
        IFile file = shipScoped
                ? findShipFile(currentProject, normalizedPath)
                : findOrCreateFile(root, normalizedPath, context);
        if (file == null) {
            return ToolResult.failure("Не удалось получить файл: " + pathStr);
        }
        if (shipScoped && !isPhysicalShipTarget(root, currentProject, file)) {
            return ToolResult.failure(
                    "GSD Ship target is not physically contained in the active workspace project"); //$NON-NLS-1$
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        boolean created = false;

        boolean allowedNewDoc = isAllowedNewDocFile(normalizedPath)
                || (shipScoped && GsdShipPathPolicy.isReleaseArtifactPath(normalizedPath));
        if (!file.exists() && !isProjectRootCodeMd(file) && !allowedNewDoc) {
            return ToolResult.failure(
                    "Создание новых файлов через write_file запрещено: " + file.getFullPath() + ". " +
                    "Разрешено создавать только Code.md в корне проекта и новые документационные файлы (*.md, *.txt) внутри проекта. " +
                    "Для модулей используйте ensure_module_artifact (Module.bsl/ObjectModule.bsl/ManagerModule.bsl), " +
                    "после чего применяйте edit_file/write_file только к существующему файлу.");
        }

        if (file.exists() && content.isBlank() && !allowEmpty && existingFileHasContent(file)) {
            logWarning("[WRITE_FILE] ЗАБЛОКИРОВАНО: пустая запись поверх непустого файла: " + file.getFullPath());
            return ToolResult.failure(
                    "write_file rejected: the new content is empty while the existing file '" + file.getFullPath() +
                    "' is not. The write was aborted to prevent data loss. " +
                    "Pass allow_empty=true to intentionally empty the file.");
        }

        if (shipScoped) {
            created = !file.exists();
            try {
                ResourcesPlugin.getWorkspace().run(monitor -> {
                    ensureParentFolderExists(file);
                    if (!isPhysicalShipTarget(root, currentProject, file)) {
                        throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                                "GSD Ship target ancestry changed before write")); //$NON-NLS-1$
                    }
                    try {
                        WorkspacePathContainment.writeContained(
                                root.getLocation().toFile().toPath(),
                                currentProject.getLocation().toFile().toPath(),
                                file.getLocation().toFile().toPath(), bytes, shipMutationHook);
                    } catch (IOException e) {
                        throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                                "GSD Ship write rejected: " + e.getMessage(), e)); //$NON-NLS-1$
                    }
                }, currentProject, 0, new NullProgressMonitor());
            } catch (CoreException e) {
                return ToolResult.failure(
                        "GSD Ship write rejected: " + e.getMessage()); //$NON-NLS-1$
            }
        } else if (file.exists()) {
            file.setContents(new ByteArrayInputStream(bytes), IResource.FORCE | IResource.KEEP_HISTORY,
                    new NullProgressMonitor());
        } else {
            ensureParentFolderExists(file);
            file.create(new ByteArrayInputStream(bytes), IResource.FORCE, new NullProgressMonitor());
            created = true;
        }

        // Refresh
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        // Wait for EDT to recompute derived data (BM) for this project, so subsequent
        // BM-backed operations (bsl_get_method_body, update_infobase, ...) see the new
        // content without requiring an EDT restart.
        boolean bmSynced = BmSyncHelper.flushAfterWrite(file);

        // Build result
        StringBuilder result = new StringBuilder();
        result.append("**Файл обновлен:** `").append(file.getFullPath()).append("`\n");
        result.append("**Размер:** ").append(bytes.length).append(" байт\n");
        result.append("**Строк:** ").append(countLines(content)).append("\n");
        result.append("**Статус:** ").append(created ? "создан" : "перезаписан").append("\n");
        result.append("**BM-синхронизация:** ")
                .append(bmSynced ? "готово" : "не подтверждена (модель может отставать)");

        logInfo((created ? "Файл создан: " : "Файл обновлен: ") + file.getFullPath());

        return ToolResult.success(result.toString(), ToolResult.ToolResultType.TEXT);
    }

    /**
     * Проверяет, содержит ли существующий файл значимые (не-whitespace) байты.
     * При ошибке чтения считает файл непустым (консервативно блокирует запись).
     */
    private boolean existingFileHasContent(IFile file) {
        try (InputStream stream = file.getContents(true)) {
            int value;
            while ((value = stream.read()) != -1) {
                if (!Character.isWhitespace(value)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
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

        return ProjectMemoryFilePolicy.canonicalizeBarePath(normalized);
    }

    /**
     * Находит или создает файл по пути.
     */
    private IFile findOrCreateFile(IWorkspaceRoot root, String path,
            ToolExecutionContext context) {
        if (path == null || path.isBlank()) {
            return null;
        }

        try {
            IPath ipath = org.eclipse.core.runtime.Path.fromPortableString(path);
            if (ipath.segmentCount() == 1) {
                IProject project = resolveCurrentProject(context);
                return project != null ? project.getFile(ipath) : null;
            }
            return root.getFile(ipath);
        } catch (Exception e) {
            logError("Ошибка получения файла: " + path, e);
            return null;
        }
    }

    private IFile findShipFile(IProject project, String normalizedPath) {
        if (project == null || normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        try {
            String relative = normalizedPath.startsWith("./") //$NON-NLS-1$
                    ? normalizedPath.substring(2) : normalizedPath;
            return project.getFile(org.eclipse.core.runtime.Path.fromPortableString(relative));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isPhysicalShipTarget(
            IWorkspaceRoot root, IProject currentProject, IFile file) {
        if (root == null || currentProject == null || file == null
                || !currentProject.equals(file.getProject())
                || WorkspacePathContainment.isLinkedResource(file)
                || root.getLocation() == null
                || currentProject.getLocation() == null
                || file.getLocation() == null) {
            return false;
        }
        return WorkspacePathContainment.isContained(
                root.getLocation().toFile().toPath(),
                currentProject.getLocation().toFile().toPath(),
                file.getLocation().toFile().toPath());
    }

    private IProject resolveCurrentProject(ToolExecutionContext context) {
        return ActiveProjectSupport.resolveActiveProject(context);
    }

    private boolean isProjectRootCodeMd(IFile file) {
        if (file == null || file.getProjectRelativePath() == null) {
            return false;
        }
        if (file.getProjectRelativePath().segmentCount() != 1) {
            return false;
        }
        return ProjectMemoryFilePolicy.isCanonicalFileName(file.getName());
    }

    /**
     * Allows creating new plain documentation files (*.md, *.txt) inside the workspace, e.g. when
     * the user explicitly asks to save notes or a report to a file. Structured EDT artifacts
     * (.mdo/.form/.mxl/DCS) are rejected earlier and never reach this check.
     */
    private boolean isAllowedNewDocFile(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".txt"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Ensures the parent folder chain of a to-be-created file exists, so new docs can be placed in
     * nested directories (e.g. {@code docs/report/summary.md}).
     */
    private void ensureParentFolderExists(IFile file) throws CoreException {
        if (file == null) {
            return;
        }
        IContainer parent = file.getParent();
        if (parent instanceof IFolder folder && !folder.exists()) {
            createFolderChain(folder);
        }
    }

    private void createFolderChain(IFolder folder) throws CoreException {
        IContainer parent = folder.getParent();
        if (parent instanceof IFolder parentFolder && !parentFolder.exists()) {
            createFolderChain(parentFolder);
        }
        if (!folder.exists()) {
            folder.create(true, true, new NullProgressMonitor());
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

    static boolean isStructuredEdtArtifactPath(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".form") //$NON-NLS-1$
                || lower.endsWith(".form.xml") //$NON-NLS-1$
                || lower.endsWith(".mxl") //$NON-NLS-1$
                || lower.endsWith("/main@datacompositionschema.xml") //$NON-NLS-1$
                || lower.endsWith("/maindatacompositionschema.xml") //$NON-NLS-1$
                || lower.contains("/ext/maindatacompositionschema."); //$NON-NLS-1$
    }

    private void logInfo(String message) {
        log.log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    private void logWarning(String message) {
        log.log(new Status(IStatus.WARNING, PLUGIN_ID, message));
    }

    private void logError(String message, Throwable error) {
        log.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }
}
