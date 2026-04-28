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
import com.codepilot1c.core.tools.util.ToolResultTruncator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

/**
 * Tool for listing files in a directory.
 */
@ToolMeta(
    name = "list_files",
    category = "file",
    tags = {"read-only", "workspace"}
)
public class ListFilesTool extends AbstractTool {

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Workspace-relative directory path to inspect. Leave empty to list workspace projects."
                    },
                    "pattern": {
                        "type": "string",
                        "description": "Optional file-name filter inside one directory, for example '*.bsl'"
                    },
                    "recursive": {
                        "type": "boolean",
                        "description": "List nested directories recursively when you need a small tree walk (default: false)"
                    }
                }
            }
            """; //$NON-NLS-1$

    private static final int MAX_FILES = 100;

    /** Upper bound in characters for the rendered tool output (token-budget cap). */
    private static final int MAX_OUTPUT_CHARS = 40000;

    @Override
    public String getDescription() {
        return "Показывает файлы и папки в каталоге workspace. Используй для обзора структуры проекта."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String path = params.optString("path", null); //$NON-NLS-1$
            String pattern = params.optString("pattern", null); //$NON-NLS-1$
            boolean recursive = params.optBoolean("recursive", false); //$NON-NLS-1$

            try {
                IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

                if (path == null || path.isEmpty()) {
                    // List projects
                    return listProjects(root);
                }

                // Normalize path for cross-platform compatibility
                String normalizedPath = normalizePath(path);

                // Find the container
                IResource resource = findWorkspaceResource(normalizedPath);
                if (resource == null || !resource.exists()) {
                    return ToolResult.failure("Path not found: " + path); //$NON-NLS-1$
                }

                if (!(resource instanceof IContainer)) {
                    return ToolResult.failure("Path is not a directory: " + path); //$NON-NLS-1$
                }

                return listContents((IContainer) resource, pattern, recursive);
            } catch (CoreException e) {
                return ToolResult.failure("Error listing files: " + e.getMessage()); //$NON-NLS-1$
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
        String normalized = path;
        if (normalized.startsWith("/") && !normalized.startsWith("//")) { //$NON-NLS-1$ //$NON-NLS-2$
            normalized = normalized.substring(1);
        }
        return normalized.replace('/', File.separatorChar).replace('\\', File.separatorChar);
    }

    /**
     * Finds a resource in the workspace by path.
     */
    private IResource findWorkspaceResource(String path) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Try direct lookup
        IResource resource = root.findMember(path);
        if (resource != null && resource.exists()) {
            return resource;
        }

        // Try with forward slashes
        String forwardSlashPath = path.replace('\\', '/');
        resource = root.findMember(forwardSlashPath);
        if (resource != null && resource.exists()) {
            return resource;
        }

        return null;
    }

    private ToolResult listProjects(IWorkspaceRoot root) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Workspace Projects:**\n\n"); //$NON-NLS-1$

        IProject[] projects = root.getProjects();
        if (projects.length == 0) {
            sb.append("No projects in workspace.\n"); //$NON-NLS-1$
        } else {
            for (IProject project : projects) {
                if (project.isOpen()) {
                    sb.append("  📁 ").append(project.getName()).append("/\n"); //$NON-NLS-1$ //$NON-NLS-2$
                } else {
                    sb.append("  📁 ").append(project.getName()).append("/ (closed)\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        return ToolResult.success(sb.toString(), ToolResult.ToolResultType.FILE_LIST);
    }

    private ToolResult listContents(IContainer container, String pattern, boolean recursive) throws CoreException {
        List<String> files = new ArrayList<>();
        collectFiles(container, pattern, recursive, "", files); //$NON-NLS-1$
        String header = "**Contents of:** `" + container.getFullPath().toString() + "`\n\n"; //$NON-NLS-1$ //$NON-NLS-2$
        return renderListContents(header, files, pattern);
    }

    /**
     * Package-private pure formatter. Extracted to make truncation behavior
     * testable without standing up an Eclipse workspace.
     */
    static ToolResult renderListContents(String header, List<String> files, String pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append(header);
        if (files.isEmpty()) {
            sb.append("No files found"); //$NON-NLS-1$
            if (pattern != null) {
                sb.append(" matching pattern: ").append(pattern); //$NON-NLS-1$
            }
            sb.append(".\n"); //$NON-NLS-1$
            return ToolResult.success(sb.toString(), ToolResult.ToolResultType.FILE_LIST);
        }

        int rendered = 0;
        int droppedEntries = 0;
        for (String file : files) {
            int projected = sb.length() + file.length() + 1;
            // Reserve a small budget for the trailing truncated_entries marker and the MAX_FILES note.
            if (projected > MAX_OUTPUT_CHARS - 96) {
                droppedEntries = files.size() - rendered;
                break;
            }
            sb.append(file).append("\n"); //$NON-NLS-1$
            rendered++;
        }

        if (droppedEntries == 0 && files.size() == MAX_FILES) {
            sb.append("\n*Results limited to ").append(MAX_FILES).append(" files.*\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (droppedEntries > 0) {
            sb.append("truncated_entries: ").append(droppedEntries).append('\n'); //$NON-NLS-1$
        }

        String capped = ToolResultTruncator.truncateText(sb.toString(), MAX_OUTPUT_CHARS);
        return ToolResult.success(capped, ToolResult.ToolResultType.FILE_LIST);
    }

    private void collectFiles(IContainer container, String pattern, boolean recursive,
                              String prefix, List<String> files) throws CoreException {
        if (files.size() >= MAX_FILES) {
            return;
        }

        IResource[] members = container.members();
        for (IResource member : members) {
            if (files.size() >= MAX_FILES) {
                break;
            }

            String name = member.getName();
            boolean matches = pattern == null || matchesPattern(name, pattern);

            if (member instanceof IContainer) {
                if (recursive) {
                    files.add(prefix + "📁 " + name + "/"); //$NON-NLS-1$ //$NON-NLS-2$
                    collectFiles((IContainer) member, pattern, true, prefix + "  ", files); //$NON-NLS-1$
                } else {
                    files.add(prefix + "📁 " + name + "/"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            } else if (matches) {
                files.add(prefix + "📄 " + name); //$NON-NLS-1$
            }
        }
    }

    private boolean matchesPattern(String name, String pattern) {
        // Simple glob pattern matching
        String regex = pattern
                .replace(".", "\\.") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("*", ".*") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("?", "."); //$NON-NLS-1$ //$NON-NLS-2$
        return name.matches(regex);
    }
}
