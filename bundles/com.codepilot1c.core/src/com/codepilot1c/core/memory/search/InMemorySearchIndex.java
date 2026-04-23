/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.codepilot1c.core.memory.MemoryEntry;

/**
 * In-memory search index using Jaccard token similarity.
 *
 * <p>This is a lightweight default implementation that requires no Lucene dependency.
 * It tokenizes content into lowercase words and computes Jaccard similarity for search
 * and deduplication. Suitable for small-to-medium memory stores (hundreds of entries).</p>
 *
 * <p>Future BM25 implementation in {@code com.codepilot1c.rag} can replace this
 * via the {@link IMemorySearchIndex} interface and OSGi service registry.</p>
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} and {@link CopyOnWriteArrayList}.</p>
 */
public final class InMemorySearchIndex implements IMemorySearchIndex {

    /** Minimum token length to include in tokenization. */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** Per-project indexed entries. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<IndexedEntry>> projectEntries =
            new ConcurrentHashMap<>();

    @Override
    public void index(String projectPath, MemoryEntry entry) {
        if (projectPath == null || entry == null) {
            return;
        }
        Set<String> tokens = tokenize(entry.getContent());
        if (tokens.isEmpty()) {
            return;
        }
        projectEntries.computeIfAbsent(projectPath, k -> new CopyOnWriteArrayList<>())
                .add(new IndexedEntry(entry, tokens));
    }

    @Override
    public List<ScoredEntry> search(String projectPath, String query, int maxResults) {
        if (projectPath == null || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        CopyOnWriteArrayList<IndexedEntry> entries = projectEntries.get(projectPath);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<ScoredEntry> scored = new ArrayList<>();
        for (IndexedEntry indexed : entries) {
            if (indexed.entry.isExpired()) {
                continue;
            }
            double sim = jaccardSimilarity(queryTokens, indexed.tokens);
            if (sim > 0.0) {
                scored.add(new ScoredEntry(indexed.entry, sim));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredEntry::score).reversed());

        return scored.size() <= maxResults ? scored : scored.subList(0, maxResults);
    }

    @Override
    public List<ScoredEntry> findSimilar(String projectPath, String content, int topK) {
        if (projectPath == null || content == null || content.isBlank()) {
            return List.of();
        }
        Set<String> contentTokens = tokenize(content);
        if (contentTokens.isEmpty()) {
            return List.of();
        }

        CopyOnWriteArrayList<IndexedEntry> entries = projectEntries.get(projectPath);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<ScoredEntry> scored = new ArrayList<>();
        for (IndexedEntry indexed : entries) {
            double sim = jaccardSimilarity(contentTokens, indexed.tokens);
            if (sim > 0.0) {
                scored.add(new ScoredEntry(indexed.entry, sim));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredEntry::score).reversed());

        return scored.size() <= topK ? scored : scored.subList(0, topK);
    }

    @Override
    public void clear(String projectPath) {
        if (projectPath != null) {
            projectEntries.remove(projectPath);
        }
    }

    // ---- Tokenization & Similarity ----

    /**
     * Tokenizes text into a set of lowercase normalized tokens.
     * Splits on non-alphanumeric (including Cyrillic) characters.
     */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        // Split on non-letter/non-digit (supports Cyrillic via Unicode categories)
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"); //$NON-NLS-1$
        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            if (part.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    /**
     * Computes Jaccard similarity: |A ∩ B| / |A ∪ B|.
     */
    static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = a.size() <= b.size() ? b : a;
        for (String token : smaller) {
            if (larger.contains(token)) {
                intersection++;
            }
        }
        if (intersection == 0) {
            return 0.0;
        }
        int union = a.size() + b.size() - intersection;
        return (double) intersection / union;
    }

    // ---- Internal ----

    private record IndexedEntry(MemoryEntry entry, Set<String> tokens) {
    }
}
