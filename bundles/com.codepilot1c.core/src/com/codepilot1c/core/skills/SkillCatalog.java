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

    private static final List<String> BUNDLED_SKILLS = List.of(
            "review", "refactor", "explain", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "architect", "validator"); //$NON-NLS-1$ //$NON-NLS-2$

    private final Path projectRoot;
    private final WorkspacePromptSourceResolver sourceResolver;

    public SkillCatalog() {
        this(resolveDefaultProjectRoot(), resolveDefaultUserHome());
    }

    public SkillCatalog(Path projectRoot, Path userHome) {
        this.projectRoot = projectRoot;
        this.sourceResolver = new WorkspacePromptSourceResolver(projectRoot, userHome);
    }

    /**
     * Discovers all skills from three scope layers.
     * <p>
     * Scope priority (highest wins on name collision):
     * <ol>
     *   <li><b>PROJECT</b> &mdash; {@code .codepilot1c/skills/} inside the project root
     *       (uses {@code put}, overrides both USER and BUNDLED).</li>
     *   <li><b>USER</b> &mdash; {@code ~/.codepilot1c/skills/} in the user home directory
     *       (uses {@code put}, overrides BUNDLED).</li>
     *   <li><b>BUNDLED</b> &mdash; skills shipped inside the core bundle
     *       (uses {@code putIfAbsent}, lowest priority).</li>
     * </ol>
     * Within each directory-based scope, an optional {@code SKILL.yaml} file in the same
     * directory as {@code SKILL.md} can override frontmatter values (see
     * {@link #parseSkillYaml(Path)}).
     */
    public List<SkillDefinition> discoverSkills() {
        Map<String, SkillDefinition> definitions = new LinkedHashMap<>();
        addBundledSkills(definitions);
        sourceResolver.userSkillRoots().forEach(root -> addDirectorySkills(root, SkillDefinition.SourceType.USER, definitions));
        sourceResolver.projectSkillRoots().forEach(root -> addDirectorySkills(root, SkillDefinition.SourceType.PROJECT, definitions));
        return List.copyOf(definitions.values());
    }

    public List<SkillDefinition> discoverVisibleSkills(boolean backendSelectedInUi) {
        SkillConfigStore store = SkillConfigStore.getInstance();
        return discoverSkills().stream()
                .filter(skill -> backendSelectedInUi || !skill.backendOnly())
                .filter(skill -> store.isEnabled(skill.name()))
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
                .filter(skill -> SkillConfigStore.getInstance().isEnabled(skill.name()))
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

                            // Apply optional SKILL.yaml overrides
                            Path yamlFile = directory.resolve("SKILL.yaml"); //$NON-NLS-1$
                            if (Files.isRegularFile(yamlFile)) {
                                definition = applyYamlOverrides(definition, yamlFile);
                            }

                            definitions.put(normalizeName(definition.name()), definition);
                        } catch (IOException e) {
                            // Ignore malformed skill file and keep discovery resilient.
                        }
                    });
        } catch (IOException e) {
            // Ignore broken discovery root and keep other layers working.
        }
    }

    /**
     * Applies overrides from a {@code SKILL.yaml} file to an existing skill definition.
     * <p>
     * Supported SKILL.yaml keys and their mapping to SkillDefinition fields:
     * <ul>
     *   <li>{@code interface.display_name} &rarr; name</li>
     *   <li>{@code interface.short_description} &rarr; description</li>
     *   <li>{@code policy.allow_implicit_invocation} &rarr; allowImplicit</li>
     *   <li>{@code policy.implicit_triggers} &rarr; implicitTriggers</li>
     *   <li>{@code dependencies.required_tools} &rarr; allowedTools</li>
     * </ul>
     */
    private SkillDefinition applyYamlOverrides(SkillDefinition base, Path yamlFile) {
        Map<String, String> yaml = parseSkillYaml(yamlFile);
        if (yaml.isEmpty()) {
            return base;
        }

        String name = yaml.containsKey("interface.display_name") //$NON-NLS-1$
                ? yaml.get("interface.display_name") : base.name(); //$NON-NLS-1$
        String description = yaml.containsKey("interface.short_description") //$NON-NLS-1$
                ? yaml.get("interface.short_description") : base.description(); //$NON-NLS-1$
        boolean allowImplicit = yaml.containsKey("policy.allow_implicit_invocation") //$NON-NLS-1$
                ? Boolean.parseBoolean(yaml.get("policy.allow_implicit_invocation")) : base.allowImplicit(); //$NON-NLS-1$
        List<String> implicitTriggers = yaml.containsKey("policy.implicit_triggers") //$NON-NLS-1$
                ? parseList(yaml.get("policy.implicit_triggers")) : base.implicitTriggers(); //$NON-NLS-1$
        List<String> allowedTools = yaml.containsKey("dependencies.required_tools") //$NON-NLS-1$
                ? parseList(yaml.get("dependencies.required_tools")) : base.allowedTools(); //$NON-NLS-1$

        return new SkillDefinition(name, description, allowedTools, base.backendOnly(),
                allowImplicit, implicitTriggers, base.body(), base.sourceType(), base.sourcePath());
    }

    /**
     * Parses a {@code SKILL.yaml} file using simple line-based parsing (no YAML library).
     * <p>
     * Returns a map of flattened dotted keys to their string values, e.g.
     * {@code "interface.display_name" -> "Code Review"}.
     * <p>
     * Supports a two-level YAML structure where top-level keys end with {@code :} and
     * child keys are indented with spaces. List values using {@code [...]} inline syntax
     * are stored as the raw bracket string for later parsing by {@link #parseList(String)}.
     *
     * @param yamlFile path to the SKILL.yaml file
     * @return flattened key-value map; empty if the file cannot be read or is malformed
     */
    private Map<String, String> parseSkillYaml(Path yamlFile) {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(yamlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return result;
        }

        String currentSection = null;
        for (String line : lines) {
            // Skip blank lines and comments
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { //$NON-NLS-1$
                continue;
            }

            int colonIndex = trimmed.indexOf(':');
            if (colonIndex <= 0) {
                continue;
            }

            String key = trimmed.substring(0, colonIndex).trim();
            String value = trimmed.substring(colonIndex + 1).trim();

            // Detect section header: no leading whitespace and value is empty or only whitespace
            if (!Character.isWhitespace(line.charAt(0)) && value.isEmpty()) {
                currentSection = key;
                continue;
            }

            // Child key under a section
            if (Character.isWhitespace(line.charAt(0)) && currentSection != null) {
                String flatKey = currentSection + "." + key; //$NON-NLS-1$
                result.put(flatKey, stripQuotes(value));
            }
        }

        return result;
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
        boolean allowImplicit = Boolean.parseBoolean(firstNonBlank(
                frontmatter.get("allow-implicit"), frontmatter.get("allow_implicit"), "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<String> implicitTriggers = parseList(firstNonBlank(
                frontmatter.get("implicit-triggers"), frontmatter.get("implicit_triggers"), "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        return new SkillDefinition(name, description, allowedTools, backendOnly,
                allowImplicit, implicitTriggers, body, sourceType, sourcePath);
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
