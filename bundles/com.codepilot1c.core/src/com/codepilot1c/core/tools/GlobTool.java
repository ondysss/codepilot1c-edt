/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Инструмент поиска файлов по glob паттерну.
 *
 * <p>Поддерживает:</p>
 * <ul>
 *   <li>Стандартные glob паттерны (*, **, ?)</li>
 *   <li>Кроссплатформенность (Windows/macOS/Linux)</li>
 *   <li>Поиск в workspace или проекте</li>
 *   <li>Ограничение количества результатов</li>
 *   <li>Сортировка по времени модификации</li>
 * </ul>
 */
public class GlobTool implements ITool {

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "pattern": {
                        "type": "string",
                        "description": "Glob pattern (e.g., '**/*.java', 'src/**/*.bsl')"
                    },
                    "path": {
                        "type": "string",
                        "description": "Directory to search in (project name or workspace-relative path, default: workspace root)"
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Maximum number of results (default: 100, max: 500)"
                    },
                    "include_hidden": {
                        "type": "boolean",
                        "description": "Include hidden files and directories (default: false)"
                    }
                },
                "required": ["pattern"]
            }
            """;

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS_LIMIT = 500;

    @Override
    public String getName() {
        return "glob";
    }

    @Override
    public String getDescription() {
        return "Находит файлы по glob паттерну. Поддерживает ** для рекурсивного поиска, " +
               "* для любых символов, ? для одного символа. Пример: **/*.java";
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String pattern = (String) parameters.get("pattern");
            if (pattern == null || pattern.isEmpty()) {
                return ToolResult.failure("Параметр pattern обязателен");
            }

            // Parse max results
            int maxResults = DEFAULT_MAX_RESULTS;
            Object maxParam = parameters.get("max_results");
            if (maxParam instanceof Number) {
                maxResults = Math.min(((Number) maxParam).intValue(), MAX_RESULTS_LIMIT);
            }

            // Include hidden files?
            boolean includeHidden = Boolean.TRUE.equals(parameters.get("include_hidden"));

            // Resolve base directory
            Path baseDir = resolveBaseDirectory((String) parameters.get("path"));
            if (baseDir == null || !Files.exists(baseDir)) {
                return ToolResult.failure("Директория не найдена");
            }

            try {
                List<Path> matches = findFiles(baseDir, pattern, maxResults, includeHidden);
                return formatResult(matches, pattern, baseDir, maxResults);
            } catch (IOException e) {
                return ToolResult.failure("Ошибка поиска: " + e.getMessage());
            }
        });
    }

    /**
     * Находит файлы по паттерну.
     */
    private List<Path> findFiles(Path baseDir, String pattern, int maxResults, boolean includeHidden)
            throws IOException {

        // Normalize pattern for cross-platform
        String normalizedPattern = normalizePattern(pattern);

        // Create matcher
        PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher("glob:" + normalizedPattern);

        List<Path> matches = new ArrayList<>();
        AtomicInteger count = new AtomicInteger(0);

        Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Skip hidden directories unless requested
                if (!includeHidden && isHidden(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // Skip common non-source directories
                String name = dir.getFileName().toString();
                if (shouldSkipDirectory(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Skip hidden files unless requested
                if (!includeHidden && isHidden(file)) {
                    return FileVisitResult.CONTINUE;
                }

                // Check against pattern
                Path relativePath = baseDir.relativize(file);
                if (matcher.matches(relativePath)) {
                    matches.add(file);
                    if (count.incrementAndGet() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Skip files we can't read
                return FileVisitResult.CONTINUE;
            }
        });

        // Sort by modification time (newest first)
        matches.sort(Comparator.comparing(this::getModificationTime).reversed());

        return matches;
    }

    /**
     * Нормализует паттерн для кроссплатформенности.
     */
    private String normalizePattern(String pattern) {
        // Convert backslashes to forward slashes
        String normalized = pattern.replace('\\', '/');

        // Ensure pattern doesn't start with /
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return normalized;
    }

    /**
     * Проверяет, является ли файл скрытым.
     */
    private boolean isHidden(Path path) {
        try {
            return Files.isHidden(path) ||
                   path.getFileName().toString().startsWith(".");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Проверяет, нужно ли пропустить директорию.
     */
    private boolean shouldSkipDirectory(String name) {
        return name.equals("node_modules") ||
               name.equals(".git") ||
               name.equals(".svn") ||
               name.equals(".hg") ||
               name.equals("target") ||
               name.equals("build") ||
               name.equals("bin") ||
               name.equals(".metadata") ||
               name.equals(".settings");
    }

    /**
     * Возвращает время модификации файла.
     */
    private long getModificationTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Определяет базовую директорию.
     */
    private Path resolveBaseDirectory(String path) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        Path workspacePath = Paths.get(root.getLocation().toOSString());

        if (path == null || path.isEmpty()) {
            return workspacePath;
        }

        // Normalize separators
        String normalized = path.replace('\\', '/');

        // Try as workspace-relative path first (handles multi-segment paths like "Project/src")
        Path resolved = workspacePath.resolve(normalized);
        if (Files.exists(resolved) && Files.isDirectory(resolved)) {
            return resolved;
        }

        // Try as single-segment project name (getProject requires exactly one segment)
        String projectName = normalized.contains("/") ? normalized.substring(0, normalized.indexOf('/')) : normalized;
        try {
            IProject project = root.getProject(projectName);
            if (project.exists() && project.getLocation() != null) {
                Path projectPath = Paths.get(project.getLocation().toOSString());
                if (normalized.contains("/")) {
                    // Append remaining path segments after project name
                    String subPath = normalized.substring(normalized.indexOf('/') + 1);
                    Path subResolved = projectPath.resolve(subPath);
                    if (Files.exists(subResolved) && Files.isDirectory(subResolved)) {
                        return subResolved;
                    }
                }
                return projectPath;
            }
        } catch (IllegalArgumentException e) {
            // getProject may throw for invalid paths; fall through
        }

        // Fallback to workspace
        return workspacePath;
    }

    /**
     * Форматирует результат поиска.
     */
    private ToolResult formatResult(List<Path> matches, String pattern,
                                    Path baseDir, int maxResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Паттерн:** `").append(pattern).append("`\n");
        sb.append("**Директория:** `").append(baseDir).append("`\n");
        sb.append("**Найдено файлов:** ").append(matches.size());

        if (matches.size() >= maxResults) {
            sb.append(" (достигнут лимит ").append(maxResults).append(")");
        }
        sb.append("\n\n");

        if (matches.isEmpty()) {
            sb.append("*Файлы не найдены*");
        } else {
            sb.append("```\n");
            for (Path match : matches) {
                // Show relative path
                Path relativePath = baseDir.relativize(match);
                sb.append(relativePath.toString().replace('\\', '/')).append("\n");
            }
            sb.append("```");
        }

        return ToolResult.success(sb.toString(), ToolResult.ToolResultType.TEXT);
    }
}
