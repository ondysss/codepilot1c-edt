/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves workspace-scoped and user-scoped prompt and skill sources.
 */
public final class WorkspacePromptSourceResolver {

    private final Path projectStart;
    private final Path userHome;

    public WorkspacePromptSourceResolver() {
        this(resolveDefaultProjectStart(), resolveDefaultUserHome());
    }

    public WorkspacePromptSourceResolver(Path projectStart, Path userHome) {
        this.projectStart = projectStart;
        this.userHome = userHome;
    }

    public List<Path> collectAncestors() {
        List<Path> reversed = new ArrayList<>();
        Path current = projectStart;
        while (current != null) {
            reversed.add(current.toAbsolutePath().normalize());
            current = current.getParent();
        }

        List<Path> ordered = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            ordered.add(reversed.get(i));
        }
        return ordered;
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
