/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves workspace-scoped and user-scoped prompt and skill sources.
 *
 * <p>Ancestor walk is bounded by project root markers ({@code .git}, {@code .codepilot},
 * {@code .codepilot1c}, {@code AGENTS.md}) following the Codex root-bounded discovery pattern.
 * This prevents loading unrelated instructions from parent directories above the project.
 */
public final class WorkspacePromptSourceResolver {

    /** Markers that indicate a project/repo root — stop ancestor walk here. */
    private static final Set<String> ROOT_MARKERS = Set.of(
            ".git", ".codepilot", ".codepilot1c", "AGENTS.md"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private final Path projectStart;
    private final Path userHome;

    public WorkspacePromptSourceResolver() {
        this(resolveDefaultProjectStart(), resolveDefaultUserHome());
    }

    public WorkspacePromptSourceResolver(Path projectStart, Path userHome) {
        this.projectStart = projectStart;
        this.userHome = userHome;
    }

    /**
     * Collects ancestor directories from project start upward, stopping at the
     * first directory that contains a root marker ({@code .git}, {@code .codepilot},
     * {@code .codepilot1c}, or {@code AGENTS.md}).
     *
     * <p>This follows the Codex root-bounded discovery pattern: instructions from
     * directories above the project root are not loaded, preventing leakage of
     * unrelated context from parent directories.
     *
     * @return ancestors ordered from filesystem root to project start
     */
    public List<Path> collectAncestors() {
        List<Path> reversed = new ArrayList<>();
        Path current = projectStart;
        while (current != null) {
            Path normalized = current.toAbsolutePath().normalize();
            reversed.add(normalized);
            // Stop at project/repo root — do not walk above it
            if (!reversed.isEmpty() && reversed.size() > 1 && isProjectRoot(normalized)) {
                break;
            }
            current = current.getParent();
        }

        List<Path> ordered = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            ordered.add(reversed.get(i));
        }
        return ordered;
    }

    /**
     * Returns true if the directory contains a project/repo root marker.
     */
    private static boolean isProjectRoot(Path directory) {
        return ROOT_MARKERS.stream()
                .anyMatch(marker -> Files.exists(directory.resolve(marker)));
    }

    public List<Path> hiddenPromptCandidates(String fileName) {
        List<Path> candidates = new ArrayList<>();
        addHiddenPromptCandidates(candidates, userHome, fileName);
        for (Path directory : collectAncestors()) {
            addHiddenPromptCandidates(candidates, directory, fileName);
        }
        return candidates;
    }

    public List<Path> systemPromptOverrideCandidates(boolean backendSelectedInUi) {
        List<Path> candidates = new ArrayList<>(hiddenPromptCandidates("system.md")); //$NON-NLS-1$
        if (backendSelectedInUi) {
            candidates.addAll(hiddenPromptCandidates("system-backend.md")); //$NON-NLS-1$
        }
        return candidates;
    }

    public List<Path> projectSkillRoots() {
        List<Path> roots = new ArrayList<>();
        addSkillRoots(roots, projectStart);
        return roots;
    }

    public List<Path> userSkillRoots() {
        List<Path> roots = new ArrayList<>();
        addSkillRoots(roots, userHome);
        return roots;
    }

    private void addHiddenPromptCandidates(List<Path> candidates, Path directory, String fileName) {
        if (directory == null || fileName == null || fileName.isBlank()) {
            return;
        }
        candidates.add(directory.resolve(".codepilot").resolve(fileName)); //$NON-NLS-1$
        candidates.add(directory.resolve(".codepilot1c").resolve(fileName)); //$NON-NLS-1$
    }

    private void addSkillRoots(List<Path> roots, Path directory) {
        if (directory == null) {
            return;
        }
        roots.add(directory.resolve(".codepilot").resolve("skills")); //$NON-NLS-1$ //$NON-NLS-2$
        roots.add(directory.resolve(".codepilot1c").resolve("skills")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Path resolveDefaultProjectStart() {
        String userDir = System.getProperty("user.dir"); //$NON-NLS-1$
        if (userDir == null || userDir.isBlank()) {
            return null;
        }
        return Path.of(userDir).toAbsolutePath().normalize();
    }

    private static Path resolveDefaultUserHome() {
        String userHome = System.getProperty("user.home"); //$NON-NLS-1$
        if (userHome == null || userHome.isBlank()) {
            return null;
        }
        return Path.of(userHome).toAbsolutePath().normalize();
    }
}
