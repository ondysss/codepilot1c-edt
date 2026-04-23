/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.prompt;

import java.util.List;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.memory.MemoryCategory;
import com.codepilot1c.core.memory.MemoryEntry;
import com.codepilot1c.core.memory.MemoryQuery;
import com.codepilot1c.core.memory.MemoryService;
import com.codepilot1c.core.memory.MemoryVisibility;
import com.codepilot1c.core.memory.retrieval.MemorySanitizer;
import com.codepilot1c.core.memory.search.MemoryRelevanceScorer;
import com.codepilot1c.core.memory.store.IMemoryStore;

/**
 * Contributes user-curated and machine-generated project memory to the system prompt.
 *
 * <p>Priority 500: after Platform Knowledge (300) and Project Metadata (400).</p>
 *
 * <p>Reads from {@code project.md} (curated) and {@code .auto-memory.md} (machine).
 * When a current user query is available, entries are ranked by relevance score
 * (keyword overlap, category weight, recency, access frequency).
 * PENDING tasks are always included first regardless of score.</p>
 *
 * <p>Uses {@link MemoryService#getStore(String)} for cached store access (Issue 1 fix).</p>
 */
public class MemoryPromptContributor implements IPromptContextContributor {

    private static final ILog LOG = Platform.getLog(MemoryPromptContributor.class);
    private static final String SECTION_ID = "Project Memory"; //$NON-NLS-1$
    private static final int PRIORITY = 500;

    /** Maximum non-pending entries to include after relevance scoring. */
    private static final int MAX_SCORED_ENTRIES = 20;

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

        // Use cached store (Issue 1 fix — no more new MarkdownMemoryStore per call)
        IMemoryStore store = MemoryService.getStore(ctx.projectPath());

        try {
            // Get all non-expired entries
            List<MemoryEntry> all = store.recall(MemoryQuery.all());

            if (all.isEmpty()) {
                return PromptSection.empty();
            }

            // Rank by relevance (PENDING always first, then scored entries)
            List<MemoryEntry> ranked = MemoryRelevanceScorer.rankByRelevance(
                    all, ctx.currentUserQuery(), MAX_SCORED_ENTRIES);

            if (ranked.isEmpty()) {
                return PromptSection.empty();
            }

            // Separate into categories for structured rendering
            List<MemoryEntry> pending = ranked.stream()
                    .filter(e -> e.getCategory() == MemoryCategory.PENDING)
                    .toList();
            List<MemoryEntry> archDecisions = ranked.stream()
                    .filter(e -> e.getCategory() == MemoryCategory.ARCHITECTURE)
                    .toList();
            List<MemoryEntry> notes = ranked.stream()
                    .filter(e -> e.getCategory() != MemoryCategory.PENDING
                            && e.getCategory() != MemoryCategory.ARCHITECTURE)
                    .toList();

            StringBuilder content = new StringBuilder();

            // 1. PENDING tasks — high visibility with attention marker
            String pendingText = MemorySanitizer.formatPendingTasks(pending);
            if (!pendingText.isBlank()) {
                content.append(pendingText);
            }

            // 2. Architecture decisions — extracted patterns and decisions
            String archText = MemorySanitizer.formatArchitectureDecisions(archDecisions);
            if (!archText.isBlank()) {
                if (!content.isEmpty()) {
                    content.append('\n');
                }
                content.append(archText);
            }

            // 3. Project notes — curated and auto-generated
            String notesText = MemorySanitizer.formatProjectNotes(notes);
            if (!notesText.isBlank()) {
                if (!content.isEmpty()) {
                    content.append('\n');
                }
                content.append(notesText);
            }

            String result = content.toString();
            int tokens = TokenEstimator.estimate(result);
            if (tokens > ctx.remainingBudget()) {
                result = TokenEstimator.truncateToFit(result, ctx.remainingBudget());
                tokens = TokenEstimator.estimate(result);
            }

            // Issue 3 fix: derive visibility from actual entries
            MemoryVisibility effectiveVisibility = deriveVisibility(ranked);

            return new PromptSection(SECTION_ID, result, tokens,
                    "project-memory", effectiveVisibility); //$NON-NLS-1$

        } catch (IMemoryStore.MemoryStoreException e) {
            LOG.warn("Failed to read project memory", e); //$NON-NLS-1$
            return PromptSection.empty();
        }
    }

    /**
     * Derives the effective visibility from included entries.
     * If any entry is CURATED, the section is CURATED; otherwise MACHINE.
     */
    private static MemoryVisibility deriveVisibility(List<MemoryEntry> entries) {
        for (MemoryEntry entry : entries) {
            if (entry.getVisibility() == MemoryVisibility.CURATED) {
                return MemoryVisibility.CURATED;
            }
        }
        return MemoryVisibility.MACHINE;
    }
}
