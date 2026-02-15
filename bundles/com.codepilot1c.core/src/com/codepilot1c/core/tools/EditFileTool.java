/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;

import com.codepilot1c.core.edit.EditBlock;
import com.codepilot1c.core.edit.FileEditApplier;
import com.codepilot1c.core.edit.FuzzyMatcher;
import com.codepilot1c.core.edit.MatchResult;
import com.codepilot1c.core.edit.SearchReplaceFormat;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for editing file contents.
 *
 * <p>Supports modifying existing files only.</p>
 */
public class EditFileTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EditFileTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path to the file (workspace-relative)"
                    },
                    "content": {
                        "type": "string",
                        "description": "New content for the file (replaces entire file)"
                    },
                    "old_text": {
                        "type": "string",
                        "description": "Text to search for and replace (for partial edits). Supports fuzzy matching."
                    },
                    "new_text": {
                        "type": "string",
                        "description": "Replacement text (used with old_text)"
                    },
                    "edits": {
                        "type": "string",
                        "description": "SEARCH/REPLACE blocks in format: <<<<<<< SEARCH\\nold code\\n=======\\nnew code\\n>>>>>>> REPLACE. Supports multiple blocks."
                    },
                    "create": {
                        "type": "boolean",
                        "description": "Deprecated. Creating new files is not allowed."
                    }
                },
                "required": ["path"]
            }
            """; //$NON-NLS-1$

    private final FuzzyMatcher fuzzyMatcher = new FuzzyMatcher();
    private final SearchReplaceFormat searchReplaceFormat = new SearchReplaceFormat();
    private final FileEditApplier fileEditApplier = new FileEditApplier(fuzzyMatcher, searchReplaceFormat);

    @Override
    public String getName() {
        return "edit_file"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Edit a file in the workspace. Can replace entire file content " + //$NON-NLS-1$
               "or perform search-and-replace operations in existing files only. " + //$NON-NLS-1$
               "⚠️ НЕ используйте для СОЗДАНИЯ новых объектов метаданных 1С (.mdo файлов)! " + //$NON-NLS-1$
               "Для top-level используйте create_metadata, для форм — create_form, для вложенных объектов — add_metadata_child."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;  // Агент может редактировать файлы без подтверждения
    }

    @Override
    public boolean isDestructive() {
        return false;  // Не требует специальной обработки
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            String pathStr = (String) parameters.get("path"); //$NON-NLS-1$
            if (pathStr == null || pathStr.isEmpty()) {
                LOG.warn("edit_file: отсутствует параметр path"); //$NON-NLS-1$
                return ToolResult.failure("Path parameter is required"); //$NON-NLS-1$
            }

            String content = (String) parameters.get("content"); //$NON-NLS-1$
            String oldText = (String) parameters.get("old_text"); //$NON-NLS-1$
            String newText = (String) parameters.get("new_text"); //$NON-NLS-1$
            String edits = (String) parameters.get("edits"); //$NON-NLS-1$
            boolean create = Boolean.TRUE.equals(parameters.get("create")); //$NON-NLS-1$

            LOG.debug("edit_file: path=%s, hasContent=%b, hasOldText=%b, hasEdits=%b, create=%b", //$NON-NLS-1$
                    LogSanitizer.truncatePath(pathStr), content != null, oldText != null, edits != null, create);

            try {
                // Normalize path for cross-platform compatibility
                String normalizedPath = normalizePath(pathStr);
                if (isMetadataDescriptorPath(normalizedPath)) {
                    LOG.warn("edit_file: заблокирована попытка редактирования metadata descriptor: %s", normalizedPath); //$NON-NLS-1$
                    return ToolResult.failure(
                            "❌ Редактирование .mdo файлов через edit_file запрещено.\n" + //$NON-NLS-1$
                            "Используйте create_metadata (top-level), create_form (формы) и add_metadata_child (вложенные объекты)."); //$NON-NLS-1$
                }

                // Find or create file in workspace
                IFile file = findWorkspaceFile(normalizedPath);

                if (file == null || !file.exists()) {
                    LOG.warn("edit_file: файл не найден: %s", pathStr); //$NON-NLS-1$
                    return ToolResult.failure(
                            "File not found: " + pathStr + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                            "Creating new files via edit_file is not allowed. " + //$NON-NLS-1$
                            "Use ensure_module_artifact to prepare Module.bsl/ObjectModule.bsl/ManagerModule.bsl first, " + //$NON-NLS-1$
                            "then edit existing module files only."); //$NON-NLS-1$
                }

                if (create) {
                    LOG.warn("edit_file: параметр create=true игнорируется и запрещен"); //$NON-NLS-1$
                }

                ToolResult result;
                if (content != null) {
                    // Replace entire file content
                    LOG.info("edit_file: замена содержимого файла %s (%d символов)", //$NON-NLS-1$
                            file.getFullPath(), content.length());
                    result = replaceContent(file, content);
                } else if (edits != null && !edits.isEmpty()) {
                    // SEARCH/REPLACE blocks format
                    LOG.info("edit_file: SEARCH/REPLACE редактирование %s", //$NON-NLS-1$
                            file.getFullPath());
                    result = applySearchReplaceEdits(file, edits);
                } else if (oldText != null && newText != null) {
                    // Search and replace with fuzzy matching
                    LOG.info("edit_file: fuzzy search-replace в %s (oldText=%d символов)", //$NON-NLS-1$
                            file.getFullPath(), oldText.length());
                    result = fuzzySearchAndReplace(file, oldText, newText);
                } else {
                    LOG.warn("edit_file: недостаточно параметров для редактирования"); //$NON-NLS-1$
                    return ToolResult.failure(
                            "Either 'content', 'edits', or both 'old_text' and 'new_text' are required"); //$NON-NLS-1$
                }

                LOG.debug("edit_file: завершено за %s, success=%b", //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startTime),
                        result.isSuccess());
                return result;

            } catch (CoreException e) {
                LOG.error("edit_file: ошибка редактирования %s: %s", pathStr, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure("Error editing file: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    /**
     * Normalizes path separators for cross-platform compatibility.
     */
    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        // Remove leading slash if present (workspace paths don't start with /)
        String normalized = path;
        if (normalized.startsWith("/") && !normalized.startsWith("//")) { //$NON-NLS-1$ //$NON-NLS-2$
            normalized = normalized.substring(1);
        }
        // Convert to platform-specific separators
        return normalized.replace('/', File.separatorChar).replace('\\', File.separatorChar);
    }

    /**
     * Finds a file in the workspace by path.
     */
    private IFile findWorkspaceFile(String path) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        LOG.debug("findWorkspaceFile: ищем файл по пути '%s'", path); //$NON-NLS-1$
        LOG.debug("findWorkspaceFile: workspace root = %s", root.getLocation()); //$NON-NLS-1$

        // Strategy 1: Try as workspace-relative path
        try {
            IResource resource = root.findMember(path);
            if (resource instanceof IFile && resource.exists()) {
                LOG.debug("findWorkspaceFile: найден через findMember: %s -> %s", //$NON-NLS-1$
                        resource.getFullPath(), resource.getLocation());
                return (IFile) resource;
            }
        } catch (Exception e) {
            LOG.debug("findWorkspaceFile: findMember failed: %s", e.getMessage()); //$NON-NLS-1$
        }

        // Strategy 2: Try using Path.fromOSString
        try {
            IFile file = root.getFile(Path.fromOSString(path));
            if (file.exists()) {
                LOG.debug("findWorkspaceFile: найден через fromOSString: %s -> %s", //$NON-NLS-1$
                        file.getFullPath(), file.getLocation());
                return file;
            }
        } catch (Exception e) {
            LOG.debug("findWorkspaceFile: fromOSString failed: %s", e.getMessage()); //$NON-NLS-1$
        }

        // Strategy 3: Try with forward slashes
        try {
            String forwardSlashPath = path.replace('\\', '/');
            IResource resource = root.findMember(forwardSlashPath);
            if (resource instanceof IFile && resource.exists()) {
                LOG.debug("findWorkspaceFile: найден через forward slashes: %s -> %s", //$NON-NLS-1$
                        resource.getFullPath(), resource.getLocation());
                return (IFile) resource;
            }
        } catch (Exception e) {
            LOG.debug("findWorkspaceFile: forward slashes failed: %s", e.getMessage()); //$NON-NLS-1$
        }

        LOG.warn("findWorkspaceFile: файл не найден: %s", path); //$NON-NLS-1$
        return null;
    }

    private boolean isMetadataDescriptorPath(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mdo"); //$NON-NLS-1$
    }

    private ToolResult replaceContent(IFile file, String content) throws CoreException {
        ByteArrayInputStream stream = new ByteArrayInputStream(
                content.getBytes(StandardCharsets.UTF_8));
        file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());

        // Refresh to ensure editors see the change
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        LOG.info("edit_file: содержимое записано в %s (%d байт)", //$NON-NLS-1$
                file.getFullPath(), content.length());

        return ToolResult.success(
                "Updated file: " + file.getFullPath().toString() + //$NON-NLS-1$
                " (location: " + file.getLocation() + ")", //$NON-NLS-1$ //$NON-NLS-2$
                ToolResult.ToolResultType.CONFIRMATION);
    }

    /**
     * Applies SEARCH/REPLACE blocks to a file using the FileEditApplier.
     */
    private ToolResult applySearchReplaceEdits(IFile file, String edits) throws CoreException {
        // Read current content
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }

        // Parse and apply edits
        List<EditBlock> blocks = searchReplaceFormat.parse(edits);
        if (blocks.isEmpty()) {
            return ToolResult.failure("No valid SEARCH/REPLACE blocks found in 'edits' parameter. " + //$NON-NLS-1$
                    "Use format: <<<<<<< SEARCH\\nold code\\n=======\\nnew code\\n>>>>>>> REPLACE"); //$NON-NLS-1$
        }

        // Validate blocks
        List<String> errors = searchReplaceFormat.validate(blocks);
        if (!errors.isEmpty()) {
            return ToolResult.failure("Invalid edit blocks: " + String.join("; ", errors)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Apply edits
        FileEditApplier.ApplyResult applyResult = fileEditApplier.apply(currentContent, blocks);

        if (!applyResult.allSuccessful()) {
            // Return detailed feedback for LLM to retry
            String feedback = applyResult.getFailureFeedback();
            LOG.warn("edit_file: не все блоки применены: %s", applyResult.getSummary()); //$NON-NLS-1$
            return ToolResult.failure(feedback);
        }

        // Write the modified content
        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                applyResult.afterContent().getBytes(charset));
        file.setContents(stream, true, true, new NullProgressMonitor());

        return ToolResult.success(
                applyResult.getSummary() + " в: " + file.getFullPath().toString(), //$NON-NLS-1$
                ToolResult.ToolResultType.CONFIRMATION);
    }

    /**
     * Search and replace with fuzzy matching support.
     */
    private ToolResult fuzzySearchAndReplace(IFile file, String oldText, String newText) throws CoreException {
        // Read current content
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }

        // Try fuzzy matching
        MatchResult matchResult = fuzzyMatcher.findMatch(oldText, currentContent);

        if (!matchResult.isSuccess()) {
            // Return detailed feedback for LLM to retry
            String feedback = matchResult.generateFeedback();
            LOG.warn("edit_file: fuzzy match не найден"); //$NON-NLS-1$
            return ToolResult.failure(feedback);
        }

        // Get the match location
        var location = matchResult.getLocation().orElseThrow();

        // Apply the replacement
        String before = currentContent.substring(0, location.getStartOffset());
        String after = currentContent.substring(location.getEndOffset());
        String newContent = before + newText + after;

        // Write with same charset
        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                newContent.getBytes(charset));
        file.setContents(stream, true, true, new NullProgressMonitor());

        String strategyInfo = matchResult.getStrategy() != null
                ? " (стратегия: " + matchResult.getStrategy().getDisplayName() + ")" //$NON-NLS-1$ //$NON-NLS-2$
                : ""; //$NON-NLS-1$

        return ToolResult.success(
                "Заменено в строках " + location.getStartLine() + "-" + location.getEndLine() + //$NON-NLS-1$ //$NON-NLS-2$
                        strategyInfo + " в: " + file.getFullPath().toString(), //$NON-NLS-1$
                ToolResult.ToolResultType.CONFIRMATION);
    }

    /**
     * Reads file content with proper encoding handling.
     */
    private String readFileContent(IFile file) {
        Charset charset = getFileCharset(file);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), charset))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (!firstLine) {
                    sb.append("\n"); //$NON-NLS-1$
                }
                // Skip BOM if present on first line
                if (firstLine && line.startsWith("\uFEFF")) { //$NON-NLS-1$
                    line = line.substring(1);
                }
                sb.append(line);
                firstLine = false;
            }
            return sb.toString();
        } catch (IOException | CoreException e) {
            LOG.error("Error reading file %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Legacy search and replace (exact match only).
     * @deprecated Use fuzzySearchAndReplace instead
     */
    @Deprecated
    private ToolResult searchAndReplace(IFile file, String oldText, String newText) throws CoreException {
        // Read current content with proper encoding
        Charset charset = getFileCharset(file);
        String currentContent;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), charset))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (!firstLine) {
                    sb.append(System.lineSeparator());
                }
                // Skip BOM if present on first line
                if (firstLine && line.startsWith("\uFEFF")) { //$NON-NLS-1$
                    line = line.substring(1);
                }
                sb.append(line);
                firstLine = false;
            }
            currentContent = sb.toString();
        } catch (IOException e) {
            return ToolResult.failure("Error reading file: " + e.getMessage()); //$NON-NLS-1$
        }

        // Check if old_text exists
        if (!currentContent.contains(oldText)) {
            return ToolResult.failure("Text not found in file: " + oldText); //$NON-NLS-1$
        }

        // Count occurrences
        int count = 0;
        int index = 0;
        while ((index = currentContent.indexOf(oldText, index)) != -1) {
            count++;
            index += oldText.length();
        }

        // Replace
        String newContent = currentContent.replace(oldText, newText);

        // Write with same charset
        ByteArrayInputStream stream = new ByteArrayInputStream(
                newContent.getBytes(charset));
        file.setContents(stream, true, true, new NullProgressMonitor());

        return ToolResult.success(
                "Replaced " + count + " occurrence(s) in: " + file.getFullPath().toString(), //$NON-NLS-1$ //$NON-NLS-2$
                ToolResult.ToolResultType.CONFIRMATION);
    }

    /**
     * Gets the charset for a file, defaulting to UTF-8.
     */
    private Charset getFileCharset(IFile file) {
        try {
            String charsetName = file.getCharset();
            if (charsetName != null) {
                return Charset.forName(charsetName);
            }
        } catch (CoreException | IllegalArgumentException e) {
            // Use default
        }
        return StandardCharsets.UTF_8;
    }
}
