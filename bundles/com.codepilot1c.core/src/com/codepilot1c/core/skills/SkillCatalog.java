/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.skills;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.codepilot1c.core.agent.prompts.WorkspacePromptSourceResolver;

/**
 * Discovers and parses project, user, and bundled skills.
 */
public final class SkillCatalog {

    private static final List<String> BUNDLED_SKILLS = List.of("review", "refactor", "explain"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private final Path projectRoot;
    private final WorkspacePromptSourceResolver sourceResolver;

    public SkillCatalog() {
        this(resolveDefaultProjectRoot(), resolveDefaultUserHome());
    }

    public SkillCatalog(Path projectRoot, Path userHome) {
        this.projectRoot = projectRoot;
        this.sourceResolver = new WorkspacePromptSourceResolver(projectRoot, userHome);
    }

    public List<SkillDefinition> discoverSkills() {
        Map<String, SkillDefinition> definitions = new LinkedHashMap<>();
        addBundledSkills(definitions);
        sourceResolver.userSkillRoots().forEach(root -> addDirectorySkills(root, SkillDefinition.SourceType.USER, definitions));
        sourceResolver.projectSkillRoots().forEach(root -> addDirectorySkills(root, SkillDefinition.SourceType.PROJECT, definitions));
        return List.copyOf(definitions.values());
    }

    public List<SkillDefinition> discoverVisibleSkills(boolean backendSelectedInUi) {
        return discoverSkills().stream()
                .filter(skill -> backendSelectedInUi || !skill.backendOnly())
                .toList();
    }

    public Optional<SkillDefinition> getSkill(String name, boolean backendSelectedInUi) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String requested = normalizeName(name);
        return discoverSkills().stream()
                .filter(skill -> normalizeName(skill.name()).equals(requested))
                .filter(skill -> backendSelectedInUi || !skill.backendOnly())
                .findFirst();
    }

    private void addBundledSkills(Map<String, SkillDefinition> definitions) {
        ClassLoader loader = SkillCatalog.class.getClassLoader();
        for (String name : BUNDLED_SKILLS) {
            String resourcePath = "skills/" + name + "/SKILL.md"; //$NON-NLS-1$ //$NON-NLS-2$
            loadBundledSkill(loader, name, resourcePath)
                    .ifPresent(definition -> definitions.putIfAbsent(normalizeName(definition.name()), definition));
        }
    }

    private Optional<SkillDefinition> loadBundledSkill(ClassLoader loader, String name, String resourcePath) {
        try (InputStream input = loader.getResourceAsStream(resourcePath)) {
            if (input != null) {
                String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return Optional.of(parseSkill(raw, name, SkillDefinition.SourceType.BUNDLED, resourcePath));
            }
        } catch (IOException e) {
            // Ignore classpath/resource read issues and try development fallback below.
        }

        Path sourcePath = resolveBundledSkillSourcePath(name);
        if (sourcePath != null && Files.isRegularFile(sourcePath)) {
            try {
                String raw = Files.readString(sourcePath, StandardCharsets.UTF_8);
                return Optional.of(parseSkill(
                        raw,
                        name,
                        SkillDefinition.SourceType.BUNDLED,
                        sourcePath.toAbsolutePath().normalize().toString()));
            } catch (IOException e) {
                // Ignore broken bundled skill source and continue discovery.
            }
        }

        return Optional.empty();
    }

    private void addDirectorySkills(Path root, SkillDefinition.SourceType type, Map<String, SkillDefinition> definitions) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try {
            Files.list(root)
                    .filter(Files::isDirectory)
                    .forEach(directory -> {
                        Path skillFile = directory.resolve("SKILL.md"); //$NON-NLS-1$
                        if (!Files.isRegularFile(skillFile)) {
                            return;
                        }
                        try {
                            String raw = Files.readString(skillFile, StandardCharsets.UTF_8);
                            SkillDefinition definition = parseSkill(raw, directory.getFileName().toString(), type,
                                    skillFile.toAbsolutePath().toString());
                            definitions.put(normalizeName(definition.name()), definition);
                        } catch (IOException e) {
                            // Ignore malformed skill file and keep discovery resilient.
                        }
                    });
        } catch (IOException e) {
            // Ignore broken discovery root and keep other layers working.
        }
    }

    private SkillDefinition parseSkill(String raw, String fallbackName, SkillDefinition.SourceType sourceType,
            String sourcePath) {
        Map<String, String> frontmatter = new LinkedHashMap<>();
        String body = raw != null ? raw.strip() : ""; //$NON-NLS-1$
        if (body.startsWith("---")) { //$NON-NLS-1$
            int secondFence = body.indexOf("\n---", 3); //$NON-NLS-1$
            if (secondFence > 0) {
                String header = body.substring(3, secondFence).strip();
                body = body.substring(secondFence + 4).strip();
                for (String line : header.split("\\R")) { //$NON-NLS-1$
                    int separator = line.indexOf(':');
                    if (separator <= 0) {
                        continue;
                    }
                    String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(separator + 1).trim();
                    frontmatter.put(key, stripQuotes(value));
                }
            }
        }

        String name = firstNonBlank(frontmatter.get("name"), fallbackName); //$NON-NLS-1$
        String description = firstNonBlank(frontmatter.get("description"), "No description provided."); //$NON-NLS-1$ //$NON-NLS-2$
        boolean backendOnly = Boolean.parseBoolean(firstNonBlank(
                frontmatter.get("backend-only"), frontmatter.get("backend_only"), "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<String> allowedTools = parseList(firstNonBlank(
                frontmatter.get("allowed-tools"), frontmatter.get("allowed_tools"), "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        return new SkillDefinition(name, description, allowedTools, backendOnly, body, sourceType, sourcePath);
    }

    private List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return Arrays.stream(normalized.split(",")) //$NON-NLS-1$
                .map(String::trim)
                .map(this::stripQuotes)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.trim();
        if ((stripped.startsWith("\"") && stripped.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
                || (stripped.startsWith("'") && stripped.endsWith("'"))) { //$NON-NLS-1$ //$NON-NLS-2$
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return ""; //$NON-NLS-1$
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private Path resolveBundledSkillSourcePath(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }

        List<Path> candidates = new ArrayList<>();
        addBundledSkillPathCandidates(candidates, projectRoot, skillName);

        Path cwd = resolveDefaultProjectRoot();
        if (cwd != null && !Objects.equals(cwd, projectRoot)) {
            addBundledSkillPathCandidates(candidates, cwd, skillName);
        }

        return candidates.stream()
                .filter(Objects::nonNull)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }

    private void addBundledSkillPathCandidates(List<Path> candidates, Path startRoot, String skillName) {
        for (Path root = startRoot; root != null; root = root.getParent()) {
            candidates.add(root.resolve("skills").resolve(skillName).resolve("SKILL.md")); //$NON-NLS-1$ //$NON-NLS-2$
            candidates.add(root.resolve("bundles").resolve("com.codepilot1c.core") //$NON-NLS-1$ //$NON-NLS-2$
                    .resolve("skills").resolve(skillName).resolve("SKILL.md")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Path resolveDefaultProjectRoot() {
        String userDir = System.getProperty("user.dir"); //$NON-NLS-1$
        if (userDir == null || userDir.isBlank()) {
            return null;
        }
        return Path.of(userDir).toAbsolutePath().normalize();
    }

    private static Path resolveDefaultUserHome() {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home).toAbsolutePath().normalize();
    }
}
