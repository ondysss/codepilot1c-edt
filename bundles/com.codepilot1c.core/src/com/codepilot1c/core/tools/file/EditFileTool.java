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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
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
import com.codepilot1c.core.edt.ast.BmSyncHelper;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for editing file contents.
 *
 * <p>Supports modifying existing files. The only create-mode exception is Code.md
 * in the current project root.</p>
 */
@ToolMeta(
    name = "edit_file",
    category = "file",
    mutating = true,
    tags = {"workspace"}
)
public class EditFileTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EditFileTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path to an existing workspace file; project-root Code.md may be created with create=true and content"
                    },
                    "content": {
                        "type": "string",
                        "description": "Full replacement content for the file; must be non-empty and is ignored when old_text/new_text or edits are provided. Prefer write_file for intentional whole-file overwrite"
                    },
                    "old_text": {
                        "type": "string",
                        "description": "Existing text to search for in partial-edit mode; supports fuzzy matching"
                    },
                    "new_text": {
                        "type": "string",
                        "description": "Replacement text used together with old_text"
                    },
                    "edits": {
                        "type": "string",
                        "description": "SEARCH/REPLACE blocks for targeted multi-edit patches inside an existing file"
                    },
                    "create": {
                        "type": "boolean",
                        "description": "Deprecated except for creating project-root Code.md with full content."
                    },
                    "allow_metadata_descriptor_edit": {
                        "type": "boolean",
                        "description": "Аварийный override: разрешить редактирование .mdo (не рекомендуется, используйте только когда BM API не покрывает кейс)."
                    }
                },
                "required": ["path"]
            }
            """; //$NON-NLS-1$

    private final FuzzyMatcher fuzzyMatcher = new FuzzyMatcher();
    private final SearchReplaceFormat searchReplaceFormat = new SearchReplaceFormat();
    private final FileEditApplier fileEditApplier = new FileEditApplier(fuzzyMatcher, searchReplaceFormat);

    @Override
    public String getDescription() {
        return "Редактирует существующий файл workspace через replace, SEARCH/REPLACE или fuzzy-патч; с create=true может создать Code.md в корне текущего проекта."; //$NON-NLS-1$
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
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return doExecute(params, ToolExecutionContext.unscoped());
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(
            ToolParameters params, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            String pathStr = params.requireString("path"); //$NON-NLS-1$

            String content = params.optString("content", null); //$NON-NLS-1$
            String oldText = params.optString("old_text", null); //$NON-NLS-1$
            String newText = params.optString("new_text", null); //$NON-NLS-1$
            String edits = params.optString("edits", null); //$NON-NLS-1$
            boolean create = params.optBoolean("create", false); //$NON-NLS-1$
            boolean allowMetadataDescriptorEdit = params.optBoolean("allow_metadata_descriptor_edit", false); //$NON-NLS-1$

            LOG.debug("edit_file: path=%s, hasContent=%b, hasOldText=%b, hasEdits=%b, create=%b", //$NON-NLS-1$
                    LogSanitizer.truncatePath(pathStr), content != null, oldText != null, edits != null, create);

            try {
                // Normalize path for cross-platform compatibility
                String normalizedPath = normalizePath(pathStr);
                if (isMetadataDescriptorPath(normalizedPath)) {
                    if (!allowMetadataDescriptorEdit) {
                        LOG.warn("edit_file: заблокирована попытка редактирования metadata descriptor без override: %s", normalizedPath); //$NON-NLS-1$
                        return ToolResult.failure(
                                "❌ Редактирование .mdo файлов по умолчанию заблокировано.\n" + //$NON-NLS-1$
                                "Сначала используйте create_metadata/create_form/add_metadata_child/update_metadata.\n" + //$NON-NLS-1$
                                "Для аварийного обхода передайте allow_metadata_descriptor_edit=true."); //$NON-NLS-1$
                    }
                    LOG.warn("edit_file: аварийный override .mdo включен для %s", normalizedPath); //$NON-NLS-1$
                }

                // FORM/DCS/TEMPLATE artifacts are structured EDT files and must be changed through semantic tools.
                if (isStructuredEdtArtifactPath(normalizedPath)) {
                    LOG.warn("edit_file: заблокирована попытка редактирования структурного EDT artifact: %s", normalizedPath); //$NON-NLS-1$
                    return ToolResult.failure(
                            "Writing structured EDT artifacts is blocked. Use semantic EDT tools instead.\n" + //$NON-NLS-1$
                            "Для форм используйте create_form/apply_form_recipe/mutate_form_model.\n" + //$NON-NLS-1$
                            "Для СКД используйте dcs_manage.\n" + //$NON-NLS-1$
                            "Для .mxl макетов используйте add_metadata_child(child_kind=Template) и render_template."); //$NON-NLS-1$
                }

                // Find or create file in workspace
                IFile file = findWorkspaceFile(normalizedPath, context);

                if (file == null || !file.exists()) {
                    IFile newFile = create ? findWorkspaceFileHandle(normalizedPath, context) : null;
                    if (content != null && isProjectRootCodeMd(newFile)) {
                        LOG.info("edit_file: создание Code.md в корне проекта %s", //$NON-NLS-1$
                                newFile.getProject().getName());
                        return createContent(newFile, content);
                    }

                    LOG.warn("edit_file: файл не найден: %s", pathStr); //$NON-NLS-1$
                    return ToolResult.failure(
                            "File not found: " + pathStr + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                            "Creating new files via edit_file is not allowed. " + //$NON-NLS-1$
                            "Exception: create=true with full content may create project-root Code.md. " + //$NON-NLS-1$
                            "Use ensure_module_artifact to prepare Module.bsl/ObjectModule.bsl/ManagerModule.bsl first, " + //$NON-NLS-1$
                            "then edit existing module files only."); //$NON-NLS-1$
                }

                if (create) {
                    LOG.warn("edit_file: параметр create=true игнорируется и запрещен"); //$NON-NLS-1$
                }

                ToolResult result;
                EditMode mode = resolveEditMode(content, edits, oldText, newText);
                if (content != null && (mode == EditMode.SEARCH_REPLACE_BLOCKS || mode == EditMode.FUZZY_REPLACE)) {
                    // A stray (often empty) 'content' next to targeted-edit params must not
                    // turn a partial edit into a full-file overwrite.
                    LOG.warn("edit_file: параметр content проигнорирован — частичные правки имеют приоритет (%s)", //$NON-NLS-1$
                            mode);
                }
                if (mode == EditMode.SEARCH_REPLACE_BLOCKS) {
                    // SEARCH/REPLACE blocks format
                    LOG.info("edit_file: SEARCH/REPLACE редактирование %s", //$NON-NLS-1$
                            file.getFullPath());
                    result = applySearchReplaceEdits(file, edits);
                } else if (mode == EditMode.FUZZY_REPLACE) {
                    // Search and replace with fuzzy matching
                    LOG.info("edit_file: fuzzy search-replace в %s (oldText=%d символов)", //$NON-NLS-1$
                            file.getFullPath(), oldText.length());
                    result = fuzzySearchAndReplace(file, oldText, newText);
                } else if (mode == EditMode.REPLACE_CONTENT) {
                    // Replace entire file content
                    LOG.info("edit_file: замена содержимого файла %s (%d символов)", //$NON-NLS-1$
                            file.getFullPath(), content.length());
                    result = replaceContent(file, content);
                } else if (mode == EditMode.EMPTY_CONTENT_ONLY) {
                    LOG.warn("edit_file: пустой content без частичных параметров — запись отклонена: %s", //$NON-NLS-1$
                            file.getFullPath());
                    return ToolResult.failure(
                            "edit_file received an empty 'content' and no 'old_text'/'new_text' or 'edits'. " + //$NON-NLS-1$
                            "The write was aborted to prevent wiping the file. " + //$NON-NLS-1$
                            "Provide targeted edits, or use write_file with overwrite=true and allow_empty=true " + //$NON-NLS-1$
                            "to intentionally empty the file."); //$NON-NLS-1$
                } else {
                    LOG.warn("edit_file: недостаточно параметров для редактирования"); //$NON-NLS-1$
                    return ToolResult.failure(
                            "Either 'content', 'edits', or both 'old_text' and 'new_text' are required"); //$NON-NLS-1$
                }

                if (result.isSuccess()) {
                    // Wait for EDT to recompute derived data (BM) for this project, so
                    // subsequent BM-backed operations (bsl_get_method_body, update_infobase,
                    // ...) see the edit without requiring an EDT restart. Covers all write
                    // paths above (replace / search-replace / fuzzy / edit-blocks).
                    BmSyncHelper.flushAfterWrite(file);
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
     * Edit mode resolved from the tool parameters.
     */
    enum EditMode {
        /** Full-file replacement with non-blank content. */
        REPLACE_CONTENT,
        /** SEARCH/REPLACE blocks from the 'edits' parameter. */
        SEARCH_REPLACE_BLOCKS,
        /** Fuzzy old_text/new_text replacement. */
        FUZZY_REPLACE,
        /** Only a blank 'content' was provided — must be rejected. */
        EMPTY_CONTENT_ONLY,
        /** No actionable parameters. */
        NONE
    }

    /**
     * Resolves the edit mode with targeted edits taking precedence over
     * full-content replacement: models often emit a stray (frequently empty)
     * 'content' field alongside old_text/new_text, and full replacement with
     * it would wipe the file. Blank 'content' never counts as a replacement.
     */
    static EditMode resolveEditMode(String content, String edits, String oldText, String newText) {
        if (edits != null && !edits.isEmpty()) {
            return EditMode.SEARCH_REPLACE_BLOCKS;
        }
        if (oldText != null && newText != null) {
            return EditMode.FUZZY_REPLACE;
        }
        if (content != null && !content.isBlank()) {
            return EditMode.REPLACE_CONTENT;
        }
        if (content != null) {
            return EditMode.EMPTY_CONTENT_ONLY;
        }
        return EditMode.NONE;
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
        normalized = normalized.replace('/', File.separatorChar).replace('\\', File.separatorChar);
        return ProjectMemoryFilePolicy.canonicalizeBarePath(normalized);
    }

    /**
     * Finds a file in the workspace by path.
     */
    private IFile findWorkspaceFile(String path, ToolExecutionContext context) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        LOG.debug("findWorkspaceFile: ищем файл по пути '%s'", path); //$NON-NLS-1$
        LOG.debug("findWorkspaceFile: workspace root = %s", root.getLocation()); //$NON-NLS-1$

        // Bare Code.md belongs to the current project root, not the workspace root.
        try {
            org.eclipse.core.runtime.IPath ipath = Path.fromOSString(path);
            if (ipath.segmentCount() == 1) {
                IProject project = resolveCurrentProject(context);
                if (project != null) {
                    IFile file = project.getFile(ipath);
                    if (file.exists()) {
                        LOG.debug("findWorkspaceFile: найден в корне текущего проекта: %s -> %s", //$NON-NLS-1$
                                file.getFullPath(), file.getLocation());
                        return file;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("findWorkspaceFile: current project lookup failed: %s", e.getMessage()); //$NON-NLS-1$
        }

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

    private IFile findWorkspaceFileHandle(String path, ToolExecutionContext context) {
        if (path == null || path.isBlank()) {
            return null;
        }

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        try {
            org.eclipse.core.runtime.IPath ipath = Path.fromOSString(path);
            if (ipath.segmentCount() == 1) {
                IProject project = resolveCurrentProject(context);
                return project != null ? project.getFile(ipath) : null;
            }
            return root.getFile(ipath);
        } catch (Exception e) {
            LOG.debug("findWorkspaceFileHandle: failed for %s: %s", path, e.getMessage()); //$NON-NLS-1$
            return null;
        }
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

    private ToolResult replaceContent(IFile file, String content) throws CoreException {
        String currentContent = readFileContent(file);
        String lineSeparator = detectLineSeparator(currentContent);
        String normalizedContent = normalizeLineEndings(content, lineSeparator);
        if (EditResultGuard.wouldWipeNonEmptyFile(currentContent, normalizedContent)) {
            LOG.warn("edit_file: запись пустого результата поверх непустого файла отклонена: %s", //$NON-NLS-1$
                    file.getFullPath());
            return ToolResult.failure(EditResultGuard.wipeRejectionMessage(file.getFullPath().toString()));
        }
        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                normalizedContent.getBytes(charset));
        file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());

        // Refresh to ensure editors see the change
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        LOG.info("edit_file: содержимое записано в %s (%d байт)", //$NON-NLS-1$
                file.getFullPath(), normalizedContent.length());

        return ToolResult.success(
                "Updated file: " + file.getFullPath().toString() + //$NON-NLS-1$
                " (location: " + file.getLocation() + ")", //$NON-NLS-1$ //$NON-NLS-2$
                ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult createContent(IFile file, String content) throws CoreException {
        String normalizedContent = normalizeLineEndings(content, System.lineSeparator());
        ByteArrayInputStream stream = new ByteArrayInputStream(
                normalizedContent.getBytes(StandardCharsets.UTF_8));
        file.create(stream, IResource.FORCE, new NullProgressMonitor());
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        LOG.info("edit_file: Code.md создан в %s (%d байт)", //$NON-NLS-1$
                file.getFullPath(), normalizedContent.length());

        return ToolResult.success(
                "Created file: " + file.getFullPath().toString() + //$NON-NLS-1$
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
        String lineSeparator = detectLineSeparator(currentContent);

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

        // Write the modified content preserving line endings
        String normalizedContent = normalizeLineEndings(applyResult.afterContent(), lineSeparator);
        if (EditResultGuard.wouldWipeNonEmptyFile(currentContent, normalizedContent)) {
            LOG.warn("edit_file: SEARCH/REPLACE результат пуст при непустом исходнике, запись отклонена: %s", //$NON-NLS-1$
                    file.getFullPath());
            return ToolResult.failure(EditResultGuard.wipeRejectionMessage(file.getFullPath().toString()));
        }
        Charset charset = getFileCharset(file);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                normalizedContent.getBytes(charset));
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
        String lineSeparator = detectLineSeparator(currentContent);

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
        String normalizedNewText = normalizeLineEndings(newText, lineSeparator);
        String newContent = before + normalizedNewText + after;
        if (EditResultGuard.wouldWipeNonEmptyFile(currentContent, newContent)) {
            LOG.warn("edit_file: fuzzy результат пуст при непустом исходнике, запись отклонена: %s", //$NON-NLS-1$
                    file.getFullPath());
            return ToolResult.failure(EditResultGuard.wipeRejectionMessage(file.getFullPath().toString()));
        }

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
        try (InputStream stream = file.getContents()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int read;
            while ((read = stream.read(data)) != -1) {
                buffer.write(data, 0, read);
            }
            String content = new String(buffer.toByteArray(), charset);
            if (content.startsWith("\uFEFF")) { //$NON-NLS-1$
                content = content.substring(1);
            }
            return content;
        } catch (IOException | CoreException e) {
            LOG.error("Error reading file %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private String detectLineSeparator(String content) {
        if (content == null || content.isEmpty()) {
            return System.lineSeparator();
        }
        int lfIndex = content.indexOf('\n');
        if (lfIndex > 0 && content.charAt(lfIndex - 1) == '\r') {
            return "\r\n"; //$NON-NLS-1$
        }
        if (content.indexOf('\r') >= 0) {
            return "\r"; //$NON-NLS-1$
        }
        if (lfIndex >= 0) {
            return "\n"; //$NON-NLS-1$
        }
        return System.lineSeparator();
    }

    private String normalizeLineEndings(String text, String lineSeparator) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (lineSeparator == null || lineSeparator.isEmpty() || "\n".equals(lineSeparator)) { //$NON-NLS-1$
            return text.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return normalized.replace("\n", lineSeparator); //$NON-NLS-1$
    }

    /**
     * Legacy search and replace (exact match only).
     * @deprecated Use fuzzySearchAndReplace instead
     */
    @Deprecated
    private ToolResult searchAndReplace(IFile file, String oldText, String newText) throws CoreException {
        String currentContent = readFileContent(file);
        if (currentContent == null) {
            return ToolResult.failure("Error reading file content"); //$NON-NLS-1$
        }
        String lineSeparator = detectLineSeparator(currentContent);

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
        String normalizedNewText = normalizeLineEndings(newText, lineSeparator);
        String newContent = currentContent.replace(oldText, normalizedNewText);

        // Write with same charset
        Charset charset = getFileCharset(file);
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
