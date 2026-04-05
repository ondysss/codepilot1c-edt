/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.prompt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.memory.MemoryVisibility;
import com.codepilot1c.core.memory.detection.ProjectContext;
import com.codepilot1c.core.memory.detection.ProjectMetadataDetector;

/**
 * Contributes pre-built 1C/EDT/BSL platform knowledge to the system prompt.
 *
 * <p>Priority 300: first memory contributor, before metadata and user memory.</p>
 *
 * <p>Knowledge files are shipped with the plugin in {@code resources/knowledge/}.
 * Selection is based on auto-detected project metadata (BSP presence,
 * managed forms, extensions, typical config).</p>
 */
public class PlatformKnowledgeContributor implements IPromptContextContributor {

    private static final ILog LOG = Platform.getLog(PlatformKnowledgeContributor.class);
    private static final String SECTION_ID = "1C Platform Knowledge"; //$NON-NLS-1$
    private static final int PRIORITY = 300;
    private static final String RESOURCE_PREFIX = "/resources/knowledge/"; //$NON-NLS-1$

    /** Cache loaded resources to avoid repeated I/O. */
    private static final Map<String, String> RESOURCE_CACHE = new ConcurrentHashMap<>();

    @Override
    public String getSectionId() {
        return SECTION_ID;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public PromptSection contribute(PromptAssemblyContext ctx) {
        if (ctx.projectPath() == null || ctx.isBudgetExhausted()) {
            return PromptSection.empty();
        }

        ProjectContext meta = ProjectMetadataDetector.getCached(ctx.projectPath());

        List<String> sections = new ArrayList<>();

        // Always include core knowledge (budget-critical: ~490 tokens total)
        addResource(sections, "bsl-best-practices.md"); //$NON-NLS-1$
        addResource(sections, "edt-gotchas.md"); //$NON-NLS-1$

        // Conditional knowledge (loaded only when project context warrants it)
        addResource(sections, "bsl-code-review-checklist.md"); //$NON-NLS-1$
        addResource(sections, "bsl-query-optimization.md"); //$NON-NLS-1$
        addResource(sections, "edt-metadata-operations.md"); //$NON-NLS-1$

        if (meta != null) {
            if (meta.hasBsp()) {
                addResource(sections, "bsp-patterns.md"); //$NON-NLS-1$
            }
            // Gate extensions.md by isExtension || hasExtensions (review finding #15)
            if (meta.isExtension() || meta.hasExtensions()) {
                addResource(sections, "extensions.md"); //$NON-NLS-1$
            }
            if (meta.hasManagedForms()) {
                addResource(sections, "managed-forms.md"); //$NON-NLS-1$
            }
            if (meta.isTypical()) {
                addResource(sections, "typical-configs.md"); //$NON-NLS-1$
            }
        }

        String content = joinAndTruncate(sections, ctx.remainingBudget());
        if (content.isBlank()) {
            return PromptSection.empty();
        }

        int tokens = TokenEstimator.estimate(content);
        return new PromptSection(SECTION_ID, content, tokens,
                "plugin-resource", MemoryVisibility.CURATED); //$NON-NLS-1$
    }

    private void addResource(List<String> sections, String fileName) {
        String content = loadResource(fileName);
        if (content != null && !content.isBlank()) {
            sections.add(content);
        }
    }

    /**
     * Loads a knowledge resource from the plugin's resources/knowledge/ directory.
     * Results are cached for the lifetime of the plugin.
     */
    static String loadResource(String fileName) {
        return RESOURCE_CACHE.computeIfAbsent(fileName, name -> {
            String path = RESOURCE_PREFIX + name;
            try (InputStream is = PlatformKnowledgeContributor.class.getResourceAsStream(path)) {
                if (is == null) {
                    // Try classloader-based loading (for OSGi bundle resources)
                    return loadViaClassLoader(path);
                }
                return readStream(is);
            } catch (IOException e) {
                LOG.warn("Failed to load knowledge resource: " + path, e); //$NON-NLS-1$
                return null;
            }
        });
    }

    private static String loadViaClassLoader(String path) {
        try (InputStream is = PlatformKnowledgeContributor.class.getClassLoader()
                .getResourceAsStream(path.startsWith("/") ? path.substring(1) : path)) { //$NON-NLS-1$
            if (is != null) {
                return readStream(is);
            }
        } catch (IOException e) {
            // Ignore
        }
        return null;
    }

    private static String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().strip();
    }

    private static String joinAndTruncate(List<String> sections, int budgetTokens) {
        StringBuilder sb = new StringBuilder();
        int remainingTokens = budgetTokens;

        for (String section : sections) {
            int sectionTokens = TokenEstimator.estimate(section);
            if (sectionTokens > remainingTokens) {
                // Truncate last section to fit
                String truncated = TokenEstimator.truncateToFit(section, remainingTokens);
                if (!truncated.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(truncated);
                }
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(section);
            remainingTokens -= sectionTokens;
        }

        return sb.toString();
    }
}
