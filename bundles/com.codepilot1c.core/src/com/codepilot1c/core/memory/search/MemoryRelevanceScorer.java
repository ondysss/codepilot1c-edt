/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.search;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.memory.MemoryCategory;
import com.codepilot1c.core.memory.MemoryEntry;

/**
 * Relevance scorer for memory entries during prompt context injection.
 *
 * <p>Scoring formula:</p>
 * <pre>
 * score = 0.35 * keyword_overlap(query, fact.content)
 *       + 0.30 * category_weight[fact.category]
 *       + 0.25 * recency(fact.timestamp)       // 2^(-hoursAgo/72)
 *       + 0.10 * access_frequency(fact)         // placeholder, always 0.5 for now
 * </pre>
 *
 * <p>PENDING entries bypass scoring and are always included.</p>
 */
public final class MemoryRelevanceScorer {

    /** Weight for keyword overlap component. */
    private static final double W_KEYWORD = 0.35;
    /** Weight for category component. */
    private static final double W_CATEGORY = 0.30;
    /** Weight for recency component. */
    private static final double W_RECENCY = 0.25;
    /** Weight for access frequency component. */
    private static final double W_FREQUENCY = 0.10;

    /** Recency half-life in hours (score halves every 72 hours). */
    private static final double RECENCY_HALF_LIFE_HOURS = 72.0;

    private MemoryRelevanceScorer() {
    }

    /**
     * Scores and ranks memory entries by relevance to the current user query.
     *
     * <p>PENDING entries are separated and always included (returned first).
     * Other entries are scored and sorted by descending relevance.</p>
     *
     * @param entries    all non-expired entries
     * @param userQuery  current user query (may be null or empty)
     * @param maxResults maximum non-pending entries to return
     * @return ranked entries: pending first, then scored entries
     */
    public static List<MemoryEntry> rankByRelevance(List<MemoryEntry> entries,
            String userQuery, int maxResults) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = InMemorySearchIndex.tokenize(userQuery);

        List<MemoryEntry> pending = new ArrayList<>();
        List<ScoredItem> scored = new ArrayList<>();

        Instant now = Instant.now();

        for (MemoryEntry entry : entries) {
            if (entry.isExpired()) {
                continue;
            }
            if (entry.getCategory() == MemoryCategory.PENDING) {
                pending.add(entry);
                continue;
            }

            double score = computeScore(entry, queryTokens, now);
            scored.add(new ScoredItem(entry, score));
        }

        scored.sort(Comparator.comparingDouble(ScoredItem::score).reversed());

        List<MemoryEntry> result = new ArrayList<>(pending);
        int count = 0;
        for (ScoredItem item : scored) {
            if (count >= maxResults) {
                break;
            }
            result.add(item.entry);
            count++;
        }

        return result;
    }

    static double computeScore(MemoryEntry entry, Set<String> queryTokens, Instant now) {
        // 1. Keyword overlap (Jaccard)
        double keywordScore = 0.0;
        if (queryTokens != null && !queryTokens.isEmpty()) {
            Set<String> entryTokens = InMemorySearchIndex.tokenize(entry.getContent());
            keywordScore = InMemorySearchIndex.jaccardSimilarity(queryTokens, entryTokens);
        }

        // 2. Category weight
        double categoryScore = categoryWeight(entry.getCategory());

        // 3. Recency: 2^(-hoursAgo/72)
        double hoursAgo = Duration.between(entry.getCreatedAt(), now).toHours();
        double recencyScore = Math.pow(2.0, -hoursAgo / RECENCY_HALF_LIFE_HOURS);
        recencyScore = Math.max(0.0, Math.min(1.0, recencyScore));

        // 4. Access frequency: placeholder (no hitCount in MemoryEntry yet)
        double frequencyScore = 0.5;

        return W_KEYWORD * keywordScore
                + W_CATEGORY * categoryScore
                + W_RECENCY * recencyScore
                + W_FREQUENCY * frequencyScore;
    }

    /**
     * Returns the category weight for the scoring formula.
     */
    static double categoryWeight(MemoryCategory category) {
        if (category == null) {
            return 0.5;
        }
        return switch (category) {
            case ARCHITECTURE -> 1.0;
            case DECISION -> 0.8;
            case BUG -> 0.7;
            case FACT -> 0.6;
            case PREFERENCE -> 0.55;
            case PATTERN -> 0.5;
            case PENDING -> 1.0; // shouldn't reach here (bypasses scoring)
        };
    }

    private record ScoredItem(MemoryEntry entry, double score) {
    }
}
