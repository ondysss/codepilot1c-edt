/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.search;

import java.util.List;

import com.codepilot1c.core.memory.MemoryEntry;

/**
 * Search index for memory entries.
 *
 * <p>Default implementation is {@link InMemorySearchIndex} (Jaccard-based).
 * Future BM25 implementation in the rag bundle can replace it via OSGi service registry.</p>
 */
public interface IMemorySearchIndex {

    /**
     * Indexes a memory entry for future search.
     *
     * @param projectPath project this entry belongs to
     * @param entry       the entry to index
     */
    void index(String projectPath, MemoryEntry entry);

    /**
     * Searches for entries similar to the given query text.
     *
     * @param projectPath project to search within
     * @param query       search query text
     * @param maxResults  maximum number of results to return
     * @return ranked list of matching entries (best first)
     */
    List<ScoredEntry> search(String projectPath, String query, int maxResults);

    /**
     * Finds entries similar to the given content for deduplication.
     *
     * @param projectPath project to search within
     * @param content     content to check for duplicates
     * @param topK        number of candidates to return
     * @return top-K similar entries with similarity scores
     */
    List<ScoredEntry> findSimilar(String projectPath, String content, int topK);

    /**
     * Removes all entries for the given project from the index.
     *
     * @param projectPath project to clear
     */
    void clear(String projectPath);

    /**
     * A memory entry with an associated relevance/similarity score.
     */
    record ScoredEntry(MemoryEntry entry, double score) {
    }
}
