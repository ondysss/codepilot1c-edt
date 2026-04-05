/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Core service for creating, updating, and deleting user skill files.
 *
 * <p>Keeps filesystem operations in core so that UI dialogs do not need
 * direct file I/O knowledge. User skills are stored under
 * {@code ~/.codepilot1c/skills/<name>/SKILL.md}.</p>
 */
public class SkillFileService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SkillFileService.class);
    private static final String SKILLS_DIR = "skills"; //$NON-NLS-1$
    private static final String SKILL_FILE = "SKILL.md"; //$NON-NLS-1$

    private static SkillFileService instance;

    /**
     * Returns the singleton instance.
     */
    public static synchronized SkillFileService getInstance() {
        if (instance == null) {
            instance = new SkillFileService();
        }
        return instance;
    }

    private SkillFileService() {
    }

    /**
     * Returns the user skills root directory ({@code ~/.codepilot1c/skills/}).
     *
     * @return path to user skills root, or null if home is unavailable
     */
    public Path getUserSkillsRoot() {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, ".codepilot1c", SKILLS_DIR); //$NON-NLS-1$
    }

    /**
     * Creates a new user skill SKILL.md file.
     *
     * @param name         skill name (must match {@code [a-z0-9-]+})
     * @param description  short description (≤200 chars for Qwen compat)
     * @param allowedTools list of tool names
     * @param backendOnly  whether skill is backend-only
     * @param body         skill instruction body
     * @return path to created SKILL.md
     * @throws IOException if file creation fails
     */
    public Path createUserSkill(String name, String description, List<String> allowedTools,
                                boolean backendOnly, String body) throws IOException {
        Path root = getUserSkillsRoot();
        if (root == null) {
            throw new IOException("User home directory is not available"); //$NON-NLS-1$
        }

        Path skillDir = root.resolve(name);
        Files.createDirectories(skillDir);

        Path skillFile = skillDir.resolve(SKILL_FILE);
        String content = buildSkillMd(name, description, allowedTools, backendOnly, body);
        Files.writeString(skillFile, content, StandardCharsets.UTF_8);

        LOG.info("Created user skill: " + skillFile); //$NON-NLS-1$
        return skillFile;
    }

    /**
     * Updates an existing SKILL.md at the given path.
     *
     * @param skillMdPath  path to existing SKILL.md
     * @param description  updated description
     * @param allowedTools updated tool list
     * @param backendOnly  updated backend-only flag
     * @param body         updated instruction body
     * @throws IOException if write fails
     */
    public void updateSkill(Path skillMdPath, String description, List<String> allowedTools,
                            boolean backendOnly, String body) throws IOException {
        if (skillMdPath == null || !Files.isRegularFile(skillMdPath)) {
            throw new IOException("SKILL.md not found: " + skillMdPath); //$NON-NLS-1$
        }
        validatePathUnderSkillsRoot(skillMdPath);

        // Derive name from directory
        String name = skillMdPath.getParent().getFileName().toString();
        String content = buildSkillMd(name, description, allowedTools, backendOnly, body);
        Files.writeString(skillMdPath, content, StandardCharsets.UTF_8);

        LOG.info("Updated skill: " + skillMdPath); //$NON-NLS-1$
    }

    /**
     * Deletes a skill by removing its SKILL.md and parent directory if empty.
     *
     * @param skillMdPath path to SKILL.md
     * @return true if deleted successfully
     */
    public boolean deleteSkill(Path skillMdPath) {
        if (skillMdPath == null || !Files.isRegularFile(skillMdPath)) {
            return false;
        }
        try {
            validatePathUnderSkillsRoot(skillMdPath);
            Path dir = skillMdPath.getParent();
            Files.deleteIfExists(skillMdPath);

            // Remove directory if empty
            if (dir != null && Files.isDirectory(dir)) {
                try (var entries = Files.list(dir)) {
                    if (entries.findFirst().isEmpty()) {
                        Files.deleteIfExists(dir);
                    }
                }
            }
            LOG.info("Deleted skill: " + skillMdPath); //$NON-NLS-1$
            return true;
        } catch (IOException e) {
            LOG.error("Failed to delete skill: " + skillMdPath, e); //$NON-NLS-1$
            return false;
        }
    }

    // ---- internal ----

    /**
     * Validates that the given path is a real file under the user skills root
     * and is not a symlink pointing outside. Prevents path traversal attacks.
     */
    private void validatePathUnderSkillsRoot(Path path) throws IOException {
        Path root = getUserSkillsRoot();
        if (root == null) {
            throw new IOException("User home directory is not available"); //$NON-NLS-1$
        }

        // Resolve real path (follows symlinks) and check containment
        Path realPath = path.toRealPath();
        Path realRoot = root.toRealPath();
        if (!realPath.startsWith(realRoot)) {
            throw new IOException("Path escapes skills root: " + path); //$NON-NLS-1$
        }
    }

    private String buildSkillMd(String name, String description, List<String> allowedTools,
                                boolean backendOnly, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("name: ").append(name).append('\n'); //$NON-NLS-1$
        sb.append("description: ").append(description != null ? description : "").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (allowedTools != null && !allowedTools.isEmpty()) {
            sb.append("allowed-tools: [").append(String.join(", ", allowedTools)).append("]\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        sb.append("backend-only: ").append(backendOnly).append('\n'); //$NON-NLS-1$
        sb.append("---\n"); //$NON-NLS-1$
        if (body != null && !body.isBlank()) {
            sb.append(body);
            if (!body.endsWith("\n")) { //$NON-NLS-1$
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
