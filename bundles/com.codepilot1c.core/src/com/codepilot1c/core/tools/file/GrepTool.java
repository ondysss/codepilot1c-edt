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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

/**
 * Tool for searching text patterns in files.
 */
@ToolMeta(
    name = "grep",
    category = "file",
    tags = {"read-only", "workspace"}
)
public class GrepTool extends AbstractTool {

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "pattern": {
                        "type": "string",
                        "description": "Plain-text or regex pattern for raw text search across files."
                    },
                    "path": {
                        "type": "string",
                        "description": "Optional workspace directory scope. Use this to narrow text search, not semantic object scope."
                    },
                    "project": {
                        "type": "string",
                        "description": "Optional EDT project scope. Without it the search covers the WHOLE workspace, which usually holds several unrelated configurations — results from a foreign project are easy to mistake for your own. Extension projects named '<project>.<extension>' are included by default."
                    },
                    "include_extensions": {
                        "type": "boolean",
                        "description": "When 'project' is set, also search its extension projects '<project>.<extension>' (default: true). An extension overrides base code, so excluding it can turn a real hit into a silent miss."
                    },
                    "file_pattern": {
                        "type": "string",
                        "description": "Optional file-name glob such as '*.bsl' or '*.xml'."
                    },
                    "regex": {
                        "type": "boolean",
                        "description": "Treat pattern as regex (default: false)"
                    },
                    "case_sensitive": {
                        "type": "boolean",
                        "description": "Case-sensitive search (default: false)"
                    },
                    "context_lines": {
                        "type": "integer",
                        "description": "Lines of context around matches (default: 0)"
                    }
                },
                "required": ["pattern"]
            }
            """; //$NON-NLS-1$

    private static final int MAX_RESULTS = 50;

    /** Upper bound in characters for the rendered tool output (token-budget cap). */
    private static final int MAX_OUTPUT_CHARS = 40000;

    @Override
    public String getDescription() {
        return "Ищет текст или regex по файлам workspace. Используй для строк, ошибок, обработчиков и литералов."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String patternStr = params.requireString("pattern"); //$NON-NLS-1$

            String path = params.optString("path", null); //$NON-NLS-1$
            String filePattern = params.optString("file_pattern", null); //$NON-NLS-1$
            boolean useRegex = params.optBoolean("regex", false); //$NON-NLS-1$
            boolean caseSensitive = params.optBoolean("case_sensitive", false); //$NON-NLS-1$
            int contextLines = params.optInt("context_lines", 0); //$NON-NLS-1$
            String project = params.optString("project", null); //$NON-NLS-1$
            boolean includeExtensions = params.optBoolean("include_extensions", true); //$NON-NLS-1$

            Pattern searchPattern;
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                if (useRegex) {
                    searchPattern = Pattern.compile(patternStr, flags);
                } else {
                    searchPattern = Pattern.compile(Pattern.quote(patternStr), flags);
                }
            } catch (PatternSyntaxException e) {
                return ToolResult.failure("Invalid regex pattern: " + e.getMessage()); //$NON-NLS-1$
            }

            PathMatcher fileMatcher;
            try {
                fileMatcher = compileFilePattern(filePattern);
            } catch (IllegalArgumentException e) {
                return ToolResult.failure("Invalid file_pattern: " + e.getMessage()); //$NON-NLS-1$
            }

            try {
                IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
                List<IContainer> searchRoots = new ArrayList<>();

                if (path != null && !path.isEmpty()) {
                    // Normalize path for cross-platform compatibility
                    String normalizedPath = normalizePath(path);
                    IResource resource = findWorkspaceResource(normalizedPath);
                    if (resource instanceof IContainer) {
                        searchRoots.add((IContainer) resource);
                    } else {
                        return ToolResult.failure("Path not found or not a directory: " + path); //$NON-NLS-1$
                    }
                } else if (project != null && !project.isEmpty()) {
                    searchRoots.addAll(resolveProjectRoots(root, project, includeExtensions));
                    if (searchRoots.isEmpty()) {
                        return ToolResult.failure("Project not found: " + project //$NON-NLS-1$
                                + ". Available projects: " + availableProjectNames(root)); //$NON-NLS-1$
                    }
                } else {
                    searchRoots.add(root);
                }

                List<SearchMatch> matches = new ArrayList<>();
                for (IContainer searchRoot : searchRoots) {
                    searchInContainer(searchRoot, searchPattern, fileMatcher, contextLines, matches);
                }

                return formatResults(patternStr, matches);
            } catch (CoreException e) {
                return ToolResult.failure("Error searching: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    /**
     * Resolves which projects to search when a project scope is given. An EDT extension lives in its
     * OWN project named "{@code <base>.<extension>}", so scoping a search to a configuration must
     * cover those too — otherwise code that overrides the base silently drops out of the results.
     * Matching is case-insensitive because project names are typed by hand.
     */
    private List<IContainer> resolveProjectRoots(IWorkspaceRoot root, String projectName,
            boolean includeExtensions) {
        List<IContainer> found = new ArrayList<>();
        String prefix = projectName + "."; //$NON-NLS-1$
        for (IProject candidate : root.getProjects()) {
            if (!candidate.isAccessible()) {
                continue;
            }
            String name = candidate.getName();
            boolean exact = name.equalsIgnoreCase(projectName);
            boolean extension = includeExtensions
                    && name.regionMatches(true, 0, prefix, 0, prefix.length());
            if (exact || extension) {
                found.add(candidate);
            }
        }
        return found;
    }

    /** Open project names, listed in the error when the requested project scope does not resolve. */
    private String availableProjectNames(IWorkspaceRoot root) {
        List<String> names = new ArrayList<>();
        for (IProject candidate : root.getProjects()) {
            if (candidate.isAccessible()) {
                names.add(candidate.getName());
            }
        }
        return String.join(", ", names); //$NON-NLS-1$
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

    private void searchInContainer(IContainer container, Pattern pattern,
                                   PathMatcher fileMatcher, int contextLines,
                                   List<SearchMatch> matches) throws CoreException {
        if (matches.size() >= MAX_RESULTS) {
            return;
        }

        IResource[] members;
        if (container instanceof IWorkspaceRoot) {
            IProject[] projects = ((IWorkspaceRoot) container).getProjects();
            for (IProject project : projects) {
                if (project.isOpen()) {
                    searchInContainer(project, pattern, fileMatcher, contextLines, matches);
                }
            }
            return;
        }

        members = container.members();
        for (IResource member : members) {
            if (matches.size() >= MAX_RESULTS) {
                break;
            }

            if (member instanceof IContainer) {
                searchInContainer((IContainer) member, pattern, fileMatcher, contextLines, matches);
            } else if (member instanceof IFile) {
                IFile file = (IFile) member;
                if (matchesFilePattern(file.getName(), fileMatcher)) {
                    searchInFile(file, pattern, contextLines, matches);
                }
            }
        }
    }

    /**
     * Default file set of a bare grep: text sources plus EDT descriptors. Every extension missing
     * from this list turns a bare search into a silent false negative — "0 matches" reads as proof
     * of absence. Omitting {@code .mdo}/{@code .form} hid metadata and form descriptors; omitting
     * {@code .rights} hid role rights, and on 2026-09-22 that cost a wrong "no references" verdict:
     * a role granting Read/View on three constants was invisible to the search, and the constants
     * were deleted as unused (the dangling rights entries were found later by a repository gate).
     */
    static final List<String> DEFAULT_EXTENSIONS = List.of(
            ".bsl", ".os", ".java", ".xml", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            ".mdo", ".form", ".dcs", ".dcss", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            ".rights", ".cmi", ".hpwa"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Compiles {@code file_pattern} into a glob matcher; {@code null} means "use the default set".
     *
     * <p>The former conversion replaced {@code .}, {@code *} and {@code ?} by hand and passed every
     * other regex metacharacter through unescaped, so a perfectly ordinary glob such as
     * {@code *.{form,mdo\}} died with {@code PatternSyntaxException: Illegal repetition} instead of
     * matching. The platform glob matcher understands braces, character classes and escaping, and
     * rejects a broken pattern with a message the caller can act on.</p>
     *
     * @throws IllegalArgumentException if the pattern is not a valid glob
     */
    static PathMatcher compileFilePattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return null;
        }
        try {
            // PatternSyntaxException is an IllegalArgumentException itself, so one catch covers both
            // the malformed-glob and the unsupported-syntax cases.
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern); //$NON-NLS-1$
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(pattern + " — " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    static boolean matchesFilePattern(String name, PathMatcher matcher) {
        if (matcher == null) {
            return DEFAULT_EXTENSIONS.stream().anyMatch(name::endsWith);
        }
        try {
            return matcher.matches(Paths.get(name));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private void searchInFile(IFile file, Pattern pattern, int contextLines,
                              List<SearchMatch> matches) throws CoreException {
        if (matches.size() >= MAX_RESULTS) {
            return;
        }

        // Get file charset
        Charset charset = getFileCharset(file);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), charset))) {

            List<String> lines = new ArrayList<>();
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                // Skip BOM on first line
                if (firstLine && line.startsWith("\uFEFF")) { //$NON-NLS-1$
                    line = line.substring(1);
                }
                firstLine = false;
                lines.add(line);
            }

            for (int i = 0; i < lines.size() && matches.size() < MAX_RESULTS; i++) {
                Matcher matcher = pattern.matcher(lines.get(i));
                if (matcher.find()) {
                    int startContext = Math.max(0, i - contextLines);
                    int endContext = Math.min(lines.size() - 1, i + contextLines);

                    StringBuilder contextBuilder = new StringBuilder();
                    for (int j = startContext; j <= endContext; j++) {
                        String prefix = (j == i) ? ">" : " "; //$NON-NLS-1$ //$NON-NLS-2$
                        contextBuilder.append(String.format("%s%4d | %s%n", prefix, j + 1, lines.get(j))); //$NON-NLS-1$
                    }

                    matches.add(new SearchMatch(
                            file.getFullPath().toString(),
                            i + 1,
                            lines.get(i).trim(),
                            contextBuilder.toString().trim()
                    ));
                }
            }
        } catch (java.io.IOException e) {
            // Skip files that can't be read
        }
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

    ToolResult formatResults(String pattern, List<SearchMatch> matches) {
        StringBuilder header = new StringBuilder();
        header.append("**Search results for:** `").append(pattern).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        header.append("**Found:** ").append(matches.size()); //$NON-NLS-1$
        if (matches.size() == MAX_RESULTS) {
            header.append("+ (limited)"); //$NON-NLS-1$
        }
        header.append(" matches\n\n"); //$NON-NLS-1$

        // Render matches progressively, dropping overflow once the text cap is hit.
        StringBuilder body = new StringBuilder();
        int rendered = 0;
        int droppedMatches = 0;
        int headerLen = header.length();
        for (SearchMatch match : matches) {
            StringBuilder chunk = new StringBuilder();
            chunk.append("**").append(match.filePath).append(":").append(match.lineNumber).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            chunk.append("```\n").append(match.context).append("\n```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            // Reserve a small budget for the trailing truncated_matches marker.
            if (headerLen + body.length() + chunk.length() > MAX_OUTPUT_CHARS - 64) {
                droppedMatches = matches.size() - rendered;
                break;
            }
            body.append(chunk);
            rendered++;
        }

        StringBuilder sb = new StringBuilder(headerLen + body.length() + 64);
        sb.append(header);
        sb.append(body);
        if (droppedMatches > 0) {
            sb.append("truncated_matches: ").append(droppedMatches).append('\n'); //$NON-NLS-1$
        }

        // Defensive final cap in case a single match already blows the budget.
        String capped = ToolResultTruncator.truncateText(sb.toString(), MAX_OUTPUT_CHARS);
        return ToolResult.success(capped, ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    static class SearchMatch {
        final String filePath;
        final int lineNumber;
        final String matchLine;
        final String context;

        SearchMatch(String filePath, int lineNumber, String matchLine, String context) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.matchLine = matchLine;
            this.context = context;
        }
    }
}
