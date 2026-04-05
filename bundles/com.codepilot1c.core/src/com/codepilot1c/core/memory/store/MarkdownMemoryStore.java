/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.codepilot1c.core.memory.MemoryCategory;
import com.codepilot1c.core.memory.MemoryEntry;
import com.codepilot1c.core.memory.MemoryQuery;
import com.codepilot1c.core.memory.MemoryScope;
import com.codepilot1c.core.memory.MemoryVisibility;
import com.codepilot1c.core.memory.RetentionPolicy;

/**
 * Markdown-based memory store using two files:
 * <ul>
 *   <li>{@code project.md} - curated by user, git-tracked</li>
 *   <li>{@code .auto-memory.md} - machine-generated, gitignored</li>
 * </ul>
 *
 * <p>Each {@code ##} heading = one MemoryEntry.
 * HTML comments {@code <!-- key:value -->} encode metadata (optional).
 * Missing {@code ##} → entire file as single entry.</p>
 */
public class MarkdownMemoryStore implements IMemoryStore {

    private static final String CURATED_FILE = "project.md"; //$NON-NLS-1$
    private static final String MACHINE_FILE = ".auto-memory.md"; //$NON-NLS-1$

    private static final Pattern HEADING_PATTERN = Pattern.compile("^##\\s+(.+)$"); //$NON-NLS-1$
    private static final Pattern METADATA_PATTERN =
            Pattern.compile("<!--\\s*(\\w+):([^>]+?)\\s*-->"); //$NON-NLS-1$

    private final Path memoryDir;

    /**
     * Creates a store for the given memory directory.
     *
     * @param memoryDir path to {@code {project}/.codepilot1c/memory/}
     */
    public MarkdownMemoryStore(Path memoryDir) {
        this.memoryDir = memoryDir;
    }

    @Override
    public void remember(MemoryEntry entry) throws MemoryStoreException {
        Path targetFile = entry.getVisibility() == MemoryVisibility.CURATED
                ? memoryDir.resolve(CURATED_FILE)
                : memoryDir.resolve(MACHINE_FILE);

        try {
            Files.createDirectories(memoryDir);
            String formatted = formatEntry(entry);
            Files.writeString(targetFile, formatted,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new MemoryStoreException("Failed to write memory entry: " + entry.getKey(), e); //$NON-NLS-1$
        }
    }

    @Override
    public List<MemoryEntry> recall(MemoryQuery query) throws MemoryStoreException {
        List<MemoryEntry> all = new ArrayList<>();
        all.addAll(parseFile(memoryDir.resolve(CURATED_FILE), MemoryVisibility.CURATED));
        all.addAll(parseFile(memoryDir.resolve(MACHINE_FILE), MemoryVisibility.MACHINE));

        return all.stream()
                .filter(e -> matchesQuery(e, query))
                .filter(e -> query.isIncludeExpired() || !e.isExpired())
                .sorted((a, b) -> {
                    // PENDING first, then by creation time (newest first)
                    if (a.isPending() != b.isPending()) {
                        return a.isPending() ? -1 : 1;
                    }
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .limit(query.getLimit())
                .toList();
    }

    @Override
    public int forgetExpired() throws MemoryStoreException {
        Path machineFile = memoryDir.resolve(MACHINE_FILE);
        if (!Files.exists(machineFile)) {
            return 0;
        }

        try {
            List<MemoryEntry> entries = parseFile(machineFile, MemoryVisibility.MACHINE);
            List<MemoryEntry> alive = entries.stream()
                    .filter(e -> !e.isExpired())
                    .toList();

            int removed = entries.size() - alive.size();
            if (removed > 0) {
                rewriteFile(machineFile, alive);
            }
            return removed;
        } catch (IOException e) {
            throw new MemoryStoreException("Failed to clean expired entries", e); //$NON-NLS-1$
        }
    }

    // --- Parsing ---

    List<MemoryEntry> parseFile(Path file, MemoryVisibility defaultVisibility) throws MemoryStoreException {
        if (!Files.exists(file)) {
            return List.of();
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return parseMarkdown(content, defaultVisibility);
        } catch (IOException e) {
            throw new MemoryStoreException("Failed to read memory file: " + file, e); //$NON-NLS-1$
        }
    }

    static List<MemoryEntry> parseMarkdown(String content, MemoryVisibility defaultVisibility) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<MemoryEntry> entries = new ArrayList<>();
        String[] lines = content.split("\\R"); //$NON-NLS-1$

        String currentKey = null;
        StringBuilder currentContent = new StringBuilder();
        MemoryCategory currentCategory = null;
        Duration currentTtl = null;
        String currentSource = null;
        Instant currentCreatedAt = null;

        for (String line : lines) {
            Matcher headingMatcher = HEADING_PATTERN.matcher(line);
            if (headingMatcher.matches()) {
                // Flush previous entry
                if (currentKey != null) {
                    entries.add(buildEntry(currentKey, currentContent.toString().strip(),
                            currentCategory, defaultVisibility, currentTtl, currentSource, currentCreatedAt));
                }
                currentKey = headingMatcher.group(1).strip();
                currentContent = new StringBuilder();
                currentCategory = inferCategory(currentKey);
                currentTtl = null;
                currentSource = null;
                currentCreatedAt = null;
                continue;
            }

            // Check for metadata comments
            Matcher metaMatcher = METADATA_PATTERN.matcher(line);
            while (metaMatcher.find()) {
                String metaKey = metaMatcher.group(1).strip();
                String metaValue = metaMatcher.group(2).strip();
                if ("retention".equals(metaKey)) { //$NON-NLS-1$
                    currentTtl = parseDuration(metaValue);
                } else if ("source".equals(metaKey)) { //$NON-NLS-1$
                    currentSource = metaValue;
                } else if ("category".equals(metaKey)) { //$NON-NLS-1$
                    currentCategory = parseCategoryOrNull(metaValue);
                } else if ("createdAt".equals(metaKey)) { //$NON-NLS-1$
                    currentCreatedAt = parseInstant(metaValue);
                }
            }

            // Skip top-level headings (#) and blank metadata lines
            if (!line.startsWith("# ") || line.startsWith("## ")) { //$NON-NLS-1$ //$NON-NLS-2$
                currentContent.append(line).append('\n');
            }
        }

        // Flush last entry
        if (currentKey != null) {
            entries.add(buildEntry(currentKey, currentContent.toString().strip(),
                    currentCategory, defaultVisibility, currentTtl, currentSource, currentCreatedAt));
        } else if (!content.isBlank()) {
            // No ## headings — entire file as single entry
            entries.add(buildEntry("project-notes", content.strip(), //$NON-NLS-1$
                    MemoryCategory.FACT, defaultVisibility, null, null, null));
        }

        return entries;
    }

    private static MemoryEntry buildEntry(String key, String content, MemoryCategory category,
            MemoryVisibility visibility, Duration ttl, String sourceSessionId, Instant createdAt) {
        // Preserve original createdAt from metadata to avoid resetting TTL on read (BLOCKER #4 fix)
        RetentionPolicy retention;
        if (ttl != null) {
            Instant effectiveCreatedAt = createdAt != null ? createdAt : Instant.now();
            retention = new RetentionPolicy(ttl, effectiveCreatedAt);
        } else {
            retention = RetentionPolicy.PERMANENT;
        }

        MemoryEntry.Builder builder = MemoryEntry.builder(key, content)
                .category(category != null ? category : MemoryCategory.FACT)
                .visibility(visibility)
                .scope(MemoryScope.PROJECT)
                .retention(retention)
                .sourceSessionId(sourceSessionId);
        if (createdAt != null) {
            builder.createdAt(createdAt);
        }
        return builder.build();
    }

    private static MemoryCategory inferCategory(String heading) {
        String lower = heading.toLowerCase();
        if (lower.contains("pending") || lower.contains("task") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("\u0437\u0430\u0434\u0430\u0447")) { // задач
            return MemoryCategory.PENDING;
        }
        if (lower.contains("architecture") || lower.contains("\u0430\u0440\u0445\u0438\u0442\u0435\u043A\u0442\u0443\u0440")) { // архитектур
            return MemoryCategory.ARCHITECTURE;
        }
        if (lower.contains("bug") || lower.contains("issue") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("\u043F\u0440\u043E\u0431\u043B\u0435\u043C")) { // проблем
            return MemoryCategory.BUG;
        }
        if (lower.contains("style") || lower.contains("coding") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("\u0441\u0442\u0438\u043B")) { // стил
            return MemoryCategory.PREFERENCE;
        }
        return MemoryCategory.FACT;
    }

    // --- Formatting ---

    private static String formatEntry(MemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## ").append(entry.getKey()).append('\n'); //$NON-NLS-1$
        sb.append(entry.getContent()).append('\n');

        // Metadata comments — one per line so METADATA_PATTERN can parse each independently
        if (entry.getRetention() != null && !entry.getRetention().ttl().isZero()) {
            sb.append("<!-- retention:").append(formatDuration(entry.getRetention().ttl())).append(" -->\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (entry.getCreatedAt() != null) {
            sb.append("<!-- createdAt:").append(entry.getCreatedAt().toString()).append(" -->\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (entry.getCategory() != null) {
            sb.append("<!-- category:").append(entry.getCategory().name()).append(" -->\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (entry.getSourceSessionId() != null) {
            sb.append("<!-- source:").append(entry.getSourceSessionId()).append(" -->\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    // --- Query matching ---

    private static boolean matchesQuery(MemoryEntry entry, MemoryQuery query) {
        if (query.getCategory() != null && entry.getCategory() != query.getCategory()) {
            return false;
        }
        if (query.getVisibility() != null && entry.getVisibility() != query.getVisibility()) {
            return false;
        }
        if (query.getKeyPattern() != null && !entry.getKey().toLowerCase()
                .contains(query.getKeyPattern().toLowerCase())) {
            return false;
        }
        return true;
    }

    // --- File rewrite ---

    private void rewriteFile(Path file, List<MemoryEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto Memory (machine-generated, do not edit manually)\n"); //$NON-NLS-1$
        for (MemoryEntry entry : entries) {
            sb.append(formatEntry(entry));
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    // --- Duration parsing ---

    private static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.strip().toLowerCase();
        try {
            if (value.endsWith("d")) { //$NON-NLS-1$
                return Duration.ofDays(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("h")) { //$NON-NLS-1$
                return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            return Duration.ofDays(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatDuration(Duration duration) {
        long days = duration.toDays();
        if (days > 0) {
            return days + "d"; //$NON-NLS-1$
        }
        return duration.toHours() + "h"; //$NON-NLS-1$
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (Exception e) {
            return null;
        }
    }

    private static MemoryCategory parseCategoryOrNull(String value) {
        try {
            return MemoryCategory.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
