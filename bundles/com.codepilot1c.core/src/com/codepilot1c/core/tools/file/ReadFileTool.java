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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for reading file contents.
 */
@ToolMeta(
    name = "read_file",
    category = "file",
    tags = {"read-only", "workspace"}
)
public class ReadFileTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ReadFileTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path to the file (workspace-relative or absolute)"
                    },
                    "start_line": {
                        "type": "integer",
                        "description": "Start line number (1-based, optional)"
                    },
                    "end_line": {
                        "type": "integer",
                        "description": "End line number (1-based, optional)"
                    }
                },
                "required": ["path"]
            }
            """; //$NON-NLS-1$

    private static final int MAX_LINES = 500;

    @Override
    public String getDescription() {
        return "Read the contents of a file. Can read the entire file or a specific line range."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            String pathStr = params.requireString("path"); //$NON-NLS-1$

            Integer startLine = params.has("start_line") ? params.optInt("start_line", 0) : null; //$NON-NLS-1$ //$NON-NLS-2$
            Integer endLine = params.has("end_line") ? params.optInt("end_line", 0) : null; //$NON-NLS-1$ //$NON-NLS-2$

            LOG.debug("read_file: path=%s, startLine=%s, endLine=%s", //$NON-NLS-1$
                    LogSanitizer.truncatePath(pathStr), startLine, endLine);

            try {
                String content = readFile(pathStr, startLine, endLine);
                long duration = System.currentTimeMillis() - startTime;
                LOG.debug("read_file: успешно прочитан %s за %s (%d символов)", //$NON-NLS-1$
                        LogSanitizer.truncatePath(pathStr), LogSanitizer.formatDuration(duration), content.length());
                return ToolResult.success(content, ToolResult.ToolResultType.CODE);
            } catch (IOException e) {
                LOG.error("read_file: ошибка чтения %s: %s", pathStr, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure("Error reading file: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String readFile(String pathStr, Integer startLine, Integer endLine) throws IOException {
        // Normalize path separators for cross-platform compatibility
        String normalizedPath = normalizePath(pathStr);

        // Find file in workspace only (security: don't allow reading arbitrary system files)
        IFile file = findWorkspaceFile(normalizedPath);
        if (file == null || !file.exists()) {
            throw new IOException("File not found in workspace: " + pathStr); //$NON-NLS-1$
        }

        // Read file with proper encoding
        List<String> lines = readFileLines(file);

        // Apply line range
        int start = startLine != null ? Math.max(1, startLine) : 1;
        int end = endLine != null ? Math.min(lines.size(), endLine) : lines.size();

        // Limit to MAX_LINES
        if (end - start + 1 > MAX_LINES) {
            end = start + MAX_LINES - 1;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**File:** `").append(file.getFullPath().toString()).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (startLine != null || endLine != null) {
            sb.append("**Lines:** ").append(start).append("-").append(end).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        sb.append("**Total lines:** ").append(lines.size()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("```\n"); //$NON-NLS-1$

        for (int i = start - 1; i < end && i < lines.size(); i++) {
            sb.append(String.format("%4d | %s%n", i + 1, lines.get(i))); //$NON-NLS-1$
        }

        sb.append("```"); //$NON-NLS-1$

        if (end < lines.size()) {
            sb.append("\n\n*File truncated. Use start_line/end_line to read more.*"); //$NON-NLS-1$
        }

        return sb.toString();
    }

    /**
     * Normalizes path separators for cross-platform compatibility.
     * Converts forward slashes to the platform-specific separator.
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
     * Tries multiple strategies to locate the file.
     */
    private IFile findWorkspaceFile(String path) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Strategy 1: Try as workspace-relative path
        try {
            IResource resource = root.findMember(path);
            if (resource instanceof IFile && resource.exists()) {
                return (IFile) resource;
            }
        } catch (Exception e) {
            // Continue to next strategy
        }

        // Strategy 2: Try using Path.fromOSString
        try {
            IFile file = root.getFile(org.eclipse.core.runtime.Path.fromOSString(path));
            if (file.exists()) {
                return file;
            }
        } catch (Exception e) {
            // Continue to next strategy
        }

        // Strategy 3: Try with forward slashes (for paths from LLM)
        try {
            String forwardSlashPath = path.replace('\\', '/');
            IResource resource = root.findMember(forwardSlashPath);
            if (resource instanceof IFile && resource.exists()) {
                return (IFile) resource;
            }
        } catch (Exception e) {
            // File not found
        }

        return null;
    }

    /**
     * Reads file lines with proper encoding detection.
     * Handles UTF-8 (with and without BOM) and Windows-1251 for BSL files.
     */
    private List<String> readFileLines(IFile file) throws IOException {
        List<String> lines = new ArrayList<>();

        // Determine charset - prefer file's declared charset, fallback to UTF-8
        Charset charset = StandardCharsets.UTF_8;
        try {
            String fileCharset = file.getCharset();
            if (fileCharset != null) {
                charset = Charset.forName(fileCharset);
            }
        } catch (CoreException | IllegalArgumentException e) {
            // Use default UTF-8
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), charset))) {
            String line;
            // Skip BOM if present
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    // Remove UTF-8 BOM if present
                    if (line.startsWith("\uFEFF")) { //$NON-NLS-1$
                        line = line.substring(1);
                    }
                    firstLine = false;
                }
                lines.add(line);
            }
        } catch (CoreException e) {
            throw new IOException("Cannot read file: " + e.getMessage(), e); //$NON-NLS-1$
        }

        return lines;
    }
}
