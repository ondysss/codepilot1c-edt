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
                        "description": "Directory path (workspace-relative). Leave empty to list projects."
                    },
                    "pattern": {
                        "type": "string",
                        "description": "File name pattern to filter (e.g., '*.bsl')"
                    },
                    "recursive": {
                        "type": "boolean",
                        "description": "Whether to list files recursively (default: false)"
                    }
                }
            }
            """; //$NON-NLS-1$

    private static final int MAX_FILES = 100;

    @Override
    public String getDescription() {
        return "List files and directories in the workspace. " + //$NON-NLS-1$
               "Can filter by pattern and list recursively."; //$NON-NLS-1$
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
        StringBuilder sb = new StringBuilder();
        sb.append("**Contents of:** `").append(container.getFullPath().toString()).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> files = new ArrayList<>();
        collectFiles(container, pattern, recursive, "", files); //$NON-NLS-1$

        if (files.isEmpty()) {
            sb.append("No files found"); //$NON-NLS-1$
            if (pattern != null) {
                sb.append(" matching pattern: ").append(pattern); //$NON-NLS-1$
            }
            sb.append(".\n"); //$NON-NLS-1$
        } else {
            for (String file : files) {
                sb.append(file).append("\n"); //$NON-NLS-1$
            }

            if (files.size() == MAX_FILES) {
                sb.append("\n*Results limited to ").append(MAX_FILES).append(" files.*\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        return ToolResult.success(sb.toString(), ToolResult.ToolResultType.FILE_LIST);
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
