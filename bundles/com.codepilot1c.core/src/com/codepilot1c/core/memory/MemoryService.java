/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.memory.detection.ProjectMetadataDetector;
import com.codepilot1c.core.memory.extraction.MemoryExtractionListener;
import com.codepilot1c.core.memory.prompt.IPromptContextContributor;
import com.codepilot1c.core.memory.prompt.MemoryPromptContributor;
import com.codepilot1c.core.memory.prompt.PlatformKnowledgeContributor;
import com.codepilot1c.core.memory.prompt.ProjectMetadataContributor;
import com.codepilot1c.core.memory.prompt.PromptContextContributorRegistry;
import com.codepilot1c.core.memory.search.IMemorySearchIndex;
import com.codepilot1c.core.memory.search.InMemorySearchIndex;
import com.codepilot1c.core.memory.store.IMemoryStore;
import com.codepilot1c.core.memory.store.IMemoryStore.MemoryStoreException;
import com.codepilot1c.core.memory.store.MarkdownMemoryStore;
import com.codepilot1c.core.session.SessionManager;

/**
 * Facade for the persistent memory subsystem.
 *
 * <p>Provides initialization, memory operations, and contributor registration.</p>
 */
public final class MemoryService {

    private static final ILog LOG = Platform.getLog(MemoryService.class);
    private static final String MEMORY_DIR = ".codepilot1c/memory"; //$NON-NLS-1$

    /** Cached store instances to prevent race conditions on parallel writes. */
    private static final ConcurrentHashMap<String, IMemoryStore> storeCache = new ConcurrentHashMap<>();

    /** Search index for memory entries (Jaccard-based dedup + search). */
    private static volatile IMemorySearchIndex searchIndex = new InMemorySearchIndex();

    /** Jaccard overlap threshold for deduplication. */
    private static final double DEDUP_THRESHOLD = 0.7;

    private static volatile boolean initialized;

    private MemoryService() {
    }

    /**
     * Initializes the memory subsystem by registering built-in contributors.
     * Safe to call multiple times (idempotent).
     */
    public static void initialize() {
        if (initialized) {
            return;
        }
        synchronized (MemoryService.class) {
            if (initialized) {
                return;
            }

            PromptContextContributorRegistry registry = PromptContextContributorRegistry.getInstance();
            registry.register(new PlatformKnowledgeContributor());
            registry.register(new ProjectMetadataContributor());
            registry.register(new MemoryPromptContributor());

            // Register memory extraction listener (BLOCKER #3 fix)
            try {
                SessionManager.getInstance().addListener(
                        new MemoryExtractionListener(() -> true));
            } catch (Exception e) {
                LOG.warn("Failed to register MemoryExtractionListener", e); //$NON-NLS-1$
            }

            initialized = true;
            LOG.info("Memory subsystem initialized with " + registry.size() + " contributors"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Returns a memory store for the given project path.
     * Instances are cached to prevent race conditions on parallel writes
     * from LLM extraction (Channel A) and RememberFactTool (Channel B).
     */
    public static IMemoryStore getStore(String projectPath) {
        return storeCache.computeIfAbsent(projectPath, path -> {
            Path memoryDir = Path.of(path).resolve(MEMORY_DIR);
            return new MarkdownMemoryStore(memoryDir);
        });
    }

    /**
     * Stores a memory entry for the given project.
     *
     * <p>Before storing, checks the search index for duplicates using Jaccard similarity.
     * If a duplicate is found (overlap > 0.7), the new entry is skipped to avoid
     * redundant facts from Channel A (LLM) and Channel B (tool) writing concurrently.</p>
     */
    public static void remember(String projectPath, MemoryEntry entry) {
        try {
            // Dedup check via search index
            if (searchIndex != null && entry.getContent() != null) {
                List<IMemorySearchIndex.ScoredEntry> similar =
                        searchIndex.findSimilar(projectPath, entry.getContent(), 3);
                for (IMemorySearchIndex.ScoredEntry scored : similar) {
                    if (scored.score() >= DEDUP_THRESHOLD) {
                        LOG.info("Memory dedup: skipping duplicate entry (overlap=" //$NON-NLS-1$
                                + String.format("%.2f", scored.score()) + "): " //$NON-NLS-1$ //$NON-NLS-2$
                                + entry.getContent().substring(0,
                                        Math.min(60, entry.getContent().length()))); //$NON-NLS-1$
                        return;
                    }
                }
            }

            getStore(projectPath).remember(entry);

            // Index the new entry for future dedup and search
            if (searchIndex != null) {
                searchIndex.index(projectPath, entry);
            }
        } catch (MemoryStoreException e) {
            LOG.warn("Failed to store memory entry: " + entry.getKey(), e); //$NON-NLS-1$
        }
    }

    /**
     * Recalls memory entries for the given project.
     */
    public static List<MemoryEntry> recall(String projectPath, MemoryQuery query) {
        try {
            return getStore(projectPath).recall(query);
        } catch (MemoryStoreException e) {
            LOG.warn("Failed to recall memory", e); //$NON-NLS-1$
            return List.of();
        }
    }

    /**
     * Cleans up expired auto-memory entries for the given project.
     */
    public static int forgetExpired(String projectPath) {
        try {
            return getStore(projectPath).forgetExpired();
        } catch (MemoryStoreException e) {
            LOG.warn("Failed to clean expired memory", e); //$NON-NLS-1$
            return 0;
        }
    }

    /**
     * Invalidates cached metadata for the given project.
     * Should be called after EDT mutation tools modify project metadata.
     */
    public static void invalidateMetadataCache(String projectPath) {
        ProjectMetadataDetector.invalidate(projectPath);
    }

    /**
     * Returns true if the memory subsystem has been initialized.
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the current search index instance.
     *
     * @return the search index (never null)
     */
    public static IMemorySearchIndex getSearchIndex() {
        return searchIndex;
    }

    /**
     * Sets a custom search index implementation (e.g., BM25 from rag bundle).
     *
     * @param index the search index to use
     */
    public static void setSearchIndex(IMemorySearchIndex index) {
        if (index != null) {
            searchIndex = index;
        }
    }

    /**
     * Resets initialization state (for testing only).
     */
    static void reset() {
        initialized = false;
        storeCache.clear();
        searchIndex = new InMemorySearchIndex();
        PromptContextContributorRegistry.getInstance().clear();
        ProjectMetadataDetector.invalidateAll();
    }
}
