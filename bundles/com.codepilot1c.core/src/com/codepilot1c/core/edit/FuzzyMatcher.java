/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Fuzzy text matcher with multiple fallback strategies.
 *
 * <p>Implements layered matching approach inspired by Aider and Cursor:
 * <ol>
 *   <li>Exact match - byte-for-byte comparison</li>
 *   <li>Whitespace normalization - ignore trailing spaces and line endings</li>
 *   <li>Indentation normalization - ignore leading whitespace differences</li>
 *   <li>Similarity-based - find best match above threshold</li>
 * </ol>
 *
 * <p>If no match is found, returns detailed feedback with candidate suggestions
 * for the LLM to retry.</p>
 */
public class FuzzyMatcher {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(FuzzyMatcher.class);

    /** Default similarity threshold for fuzzy matching */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.75;

    /** Maximum number of candidates to return in feedback */
    private static final int MAX_CANDIDATES = 5;

    private final double similarityThreshold;

    /**
     * Creates a new fuzzy matcher with default threshold.
     */
    public FuzzyMatcher() {
        this(DEFAULT_SIMILARITY_THRESHOLD);
    }

    /**
     * Creates a new fuzzy matcher with custom threshold.
     *
     * @param similarityThreshold minimum similarity (0.0 - 1.0) for fuzzy match
     */
    public FuzzyMatcher(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * Finds a match for the search text in the document.
     *
     * <p>Tries strategies in order: EXACT → NORMALIZE_WHITESPACE →
     * NORMALIZE_INDENTATION → SIMILARITY</p>
     *
     * @param searchText the text to find
     * @param documentContent the document to search in
     * @return match result with location or feedback
     */
    public MatchResult findMatch(String searchText, String documentContent) {
        if (searchText == null || searchText.isEmpty()) {
            return MatchResult.failure("Поисковый текст не может быть пустым"); //$NON-NLS-1$
        }
        if (documentContent == null || documentContent.isEmpty()) {
            return MatchResult.failure("Документ пуст"); //$NON-NLS-1$
        }

        LOG.debug("FuzzyMatcher: ищем текст длиной %d в документе длиной %d", //$NON-NLS-1$
                searchText.length(), documentContent.length());

        // Try strategies in order
        for (MatchStrategy strategy : MatchStrategy.values()) {
            MatchResult result = tryStrategy(searchText, documentContent, strategy);
            if (result.isSuccess()) {
                LOG.debug("FuzzyMatcher: найдено совпадение стратегией %s", strategy); //$NON-NLS-1$
                return result;
            }
        }

        // No match found - generate feedback
        LOG.debug("FuzzyMatcher: совпадение не найдено, генерируем feedback"); //$NON-NLS-1$
        return generateFailureFeedback(searchText, documentContent);
    }

    /**
     * Tries a specific matching strategy.
     *
     * @param searchText the text to find
     * @param documentContent the document to search in
     * @param strategy the strategy to use
     * @return match result
     */
    private MatchResult tryStrategy(String searchText, String documentContent, MatchStrategy strategy) {
        return switch (strategy) {
            case EXACT -> tryExactMatch(searchText, documentContent);
            case NORMALIZE_WHITESPACE -> tryWhitespaceNormalizedMatch(searchText, documentContent);
            case NORMALIZE_INDENTATION -> tryIndentationNormalizedMatch(searchText, documentContent);
            case SIMILARITY -> trySimilarityMatch(searchText, documentContent);
        };
    }

    /**
     * Exact byte-for-byte match.
     */
    private MatchResult tryExactMatch(String searchText, String documentContent) {
        int index = documentContent.indexOf(searchText);
        if (index >= 0) {
            // Check for uniqueness
            int secondIndex = documentContent.indexOf(searchText, index + 1);
            if (secondIndex >= 0) {
                // Multiple matches - need more context
                List<MatchResult.SimilarMatch> candidates = findAllOccurrences(searchText, documentContent);
                return MatchResult.ambiguous(candidates);
            }
            return createSuccessResult(index, searchText.length(), documentContent, MatchStrategy.EXACT, 1.0);
        }
        return MatchResult.failure("Точное совпадение не найдено"); //$NON-NLS-1$
    }

    /**
     * Match with whitespace normalization.
     */
    private MatchResult tryWhitespaceNormalizedMatch(String searchText, String documentContent) {
        String normalizedSearch = normalizeWhitespace(searchText);
        String normalizedDoc = normalizeWhitespace(documentContent);

        int normalizedIndex = normalizedDoc.indexOf(normalizedSearch);
        if (normalizedIndex < 0) {
            return MatchResult.failure("Совпадение не найдено после нормализации пробелов"); //$NON-NLS-1$
        }

        // Map back to original offsets
        int originalStart = mapNormalizedToOriginal(documentContent, normalizedDoc, normalizedIndex);
        int originalEnd = findOriginalEnd(documentContent, originalStart, searchText, normalizedSearch.length());

        // Check uniqueness
        int secondIndex = normalizedDoc.indexOf(normalizedSearch, normalizedIndex + 1);
        if (secondIndex >= 0) {
            List<MatchResult.SimilarMatch> candidates = new ArrayList<>();
            candidates.add(extractCandidate(documentContent, originalStart, originalEnd));
            int secondOriginalStart = mapNormalizedToOriginal(documentContent, normalizedDoc, secondIndex);
            int secondOriginalEnd = findOriginalEnd(documentContent, secondOriginalStart, searchText, normalizedSearch.length());
            candidates.add(extractCandidate(documentContent, secondOriginalStart, secondOriginalEnd));
            return MatchResult.ambiguous(candidates);
        }

        return createSuccessResult(originalStart, originalEnd - originalStart, documentContent,
                MatchStrategy.NORMALIZE_WHITESPACE, 0.95);
    }

    /**
     * Match with indentation normalization.
     */
    private MatchResult tryIndentationNormalizedMatch(String searchText, String documentContent) {
        // Split into lines and strip leading whitespace
        String[] searchLines = searchText.split("\n", -1); //$NON-NLS-1$
        String[] docLines = documentContent.split("\n", -1); //$NON-NLS-1$

        // Create search pattern from stripped lines
        String[] strippedSearch = new String[searchLines.length];
        for (int i = 0; i < searchLines.length; i++) {
            strippedSearch[i] = searchLines[i].stripLeading();
        }

        // Find matching sequence
        int matchStart = -1;
        int matchEnd = -1;

        for (int docStart = 0; docStart <= docLines.length - searchLines.length; docStart++) {
            boolean match = true;
            for (int i = 0; i < searchLines.length; i++) {
                String strippedDoc = docLines[docStart + i].stripLeading();
                if (!strippedSearch[i].equals(strippedDoc)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                if (matchStart >= 0) {
                    // Multiple matches
                    return MatchResult.failure("Несколько совпадений после нормализации отступов. " + //$NON-NLS-1$
                            "Добавьте больше контекста."); //$NON-NLS-1$
                }
                matchStart = docStart;
                matchEnd = docStart + searchLines.length;
            }
        }

        if (matchStart >= 0) {
            // Convert line numbers to offsets
            int startOffset = getLineOffset(documentContent, matchStart);
            int endOffset = getLineEndOffset(documentContent, matchEnd - 1);

            return createSuccessResult(startOffset, endOffset - startOffset, documentContent,
                    MatchStrategy.NORMALIZE_INDENTATION, 0.9);
        }

        return MatchResult.failure("Совпадение не найдено после нормализации отступов"); //$NON-NLS-1$
    }

    /**
     * Similarity-based fuzzy match using longest common subsequence.
     */
    private MatchResult trySimilarityMatch(String searchText, String documentContent) {
        String[] searchLines = searchText.split("\n", -1); //$NON-NLS-1$
        String[] docLines = documentContent.split("\n", -1); //$NON-NLS-1$

        if (searchLines.length == 0 || docLines.length == 0) {
            return MatchResult.failure("Пустой текст для сравнения"); //$NON-NLS-1$
        }

        double bestSimilarity = 0;
        int bestStart = -1;
        int bestEnd = -1;
        List<MatchResult.SimilarMatch> candidates = new ArrayList<>();

        // Sliding window approach
        int windowSize = searchLines.length;
        for (int docStart = 0; docStart <= docLines.length - windowSize; docStart++) {
            // Build window text
            StringBuilder windowBuilder = new StringBuilder();
            for (int i = 0; i < windowSize; i++) {
                if (i > 0) windowBuilder.append("\n"); //$NON-NLS-1$
                windowBuilder.append(docLines[docStart + i]);
            }
            String windowText = windowBuilder.toString();

            double similarity = calculateSimilarity(searchText, windowText);

            if (similarity >= similarityThreshold) {
                int startOffset = getLineOffset(documentContent, docStart);
                int endOffset = getLineEndOffset(documentContent, docStart + windowSize - 1);
                String matchedText = documentContent.substring(startOffset, endOffset);

                candidates.add(new MatchResult.SimilarMatch(
                        matchedText, docStart + 1, docStart + windowSize, similarity));

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestStart = startOffset;
                    bestEnd = endOffset;
                }
            }
        }

        if (bestStart >= 0) {
            // Check if we have multiple high-quality matches
            candidates.sort(Comparator.comparingDouble(MatchResult.SimilarMatch::similarity).reversed());
            if (candidates.size() > 1 && candidates.get(1).similarity() > similarityThreshold + 0.1) {
                // Multiple good matches - ambiguous
                return MatchResult.ambiguous(candidates.subList(0, Math.min(candidates.size(), MAX_CANDIDATES)));
            }

            return createSuccessResult(bestStart, bestEnd - bestStart, documentContent,
                    MatchStrategy.SIMILARITY, bestSimilarity);
        }

        return MatchResult.failure("Похожий текст не найден (порог сходства: " + //$NON-NLS-1$
                String.format("%.0f%%", similarityThreshold * 100) + ")"); //$NON-NLS-1$
    }

    /**
     * Generates failure feedback with candidate suggestions.
     */
    private MatchResult generateFailureFeedback(String searchText, String documentContent) {
        List<MatchResult.SimilarMatch> candidates = findSimilarCandidates(searchText, documentContent);

        if (candidates.isEmpty()) {
            return MatchResult.failure(
                    "Текст не найден в файле. Проверьте, что файл содержит искомый фрагмент."); //$NON-NLS-1$
        }

        return MatchResult.failure(
                "Точное совпадение не найдено, но есть похожие фрагменты.", //$NON-NLS-1$
                candidates);
    }

    /**
     * Finds candidates similar to search text.
     */
    private List<MatchResult.SimilarMatch> findSimilarCandidates(String searchText, String documentContent) {
        String[] searchLines = searchText.split("\n", -1); //$NON-NLS-1$
        String[] docLines = documentContent.split("\n", -1); //$NON-NLS-1$

        List<MatchResult.SimilarMatch> candidates = new ArrayList<>();
        int windowSize = Math.max(1, searchLines.length);

        // Lower threshold for candidate finding
        double candidateThreshold = similarityThreshold * 0.5;

        for (int docStart = 0; docStart <= docLines.length - windowSize; docStart++) {
            StringBuilder windowBuilder = new StringBuilder();
            for (int i = 0; i < windowSize; i++) {
                if (i > 0) windowBuilder.append("\n"); //$NON-NLS-1$
                windowBuilder.append(docLines[docStart + i]);
            }
            String windowText = windowBuilder.toString();

            double similarity = calculateSimilarity(searchText, windowText);
            if (similarity >= candidateThreshold) {
                candidates.add(new MatchResult.SimilarMatch(
                        windowText, docStart + 1, docStart + windowSize, similarity));
            }
        }

        // Sort by similarity and limit
        candidates.sort(Comparator.comparingDouble(MatchResult.SimilarMatch::similarity).reversed());
        return candidates.subList(0, Math.min(candidates.size(), MAX_CANDIDATES));
    }

    /**
     * Finds all exact occurrences of a text.
     */
    private List<MatchResult.SimilarMatch> findAllOccurrences(String text, String documentContent) {
        List<MatchResult.SimilarMatch> occurrences = new ArrayList<>();
        int index = 0;
        while ((index = documentContent.indexOf(text, index)) >= 0) {
            int startLine = countLines(documentContent.substring(0, index)) + 1;
            int endLine = startLine + countLines(text);
            occurrences.add(new MatchResult.SimilarMatch(text, startLine, endLine, 1.0));
            index += text.length();
        }
        return occurrences;
    }

    /**
     * Creates a success result with proper line numbers.
     */
    private MatchResult createSuccessResult(int startOffset, int length, String documentContent,
                                            MatchStrategy strategy, double similarity) {
        int endOffset = startOffset + length;
        String matchedText = documentContent.substring(startOffset, endOffset);
        int startLine = countLines(documentContent.substring(0, startOffset)) + 1;
        int endLine = startLine + countLines(matchedText);

        MatchLocation location = new MatchLocation(startOffset, endOffset, startLine, endLine, matchedText);
        return MatchResult.success(location, strategy, similarity);
    }

    /**
     * Normalizes whitespace in text.
     */
    private String normalizeWhitespace(String text) {
        // Normalize line endings
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // Remove trailing whitespace from each line
        String[] lines = normalized.split("\n", -1); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n"); //$NON-NLS-1$
            sb.append(lines[i].stripTrailing());
        }
        return sb.toString();
    }

    /**
     * Maps normalized index back to original document offset.
     */
    private int mapNormalizedToOriginal(String original, String normalized, int normalizedIndex) {
        // Simple approximation - count characters, adjusting for removed spaces
        int originalIndex = 0;
        int normalizedPos = 0;

        String normalizedOriginal = normalizeWhitespace(original);
        while (normalizedPos < normalizedIndex && originalIndex < original.length()) {
            char origChar = original.charAt(originalIndex);
            if (normalizedPos < normalizedOriginal.length()) {
                char normChar = normalizedOriginal.charAt(normalizedPos);
                if (origChar == normChar || (origChar == '\r' && normChar == '\n')) {
                    normalizedPos++;
                }
            }
            originalIndex++;
        }
        return originalIndex;
    }

    /**
     * Finds the end offset in original document for the matched search text.
     */
    private int findOriginalEnd(String documentContent, int originalStart, String searchText, int normalizedLength) {
        String[] searchLines = searchText.split("\n", -1); //$NON-NLS-1$
        int lineCount = searchLines.length;

        // Trailing empty element from split means searchText ends with \n
        boolean trailingNewline = lineCount > 0 && searchLines[lineCount - 1].isEmpty();
        if (trailingNewline) {
            lineCount--;
        }

        int pos = originalStart;
        int linesConsumed = 0;

        while (pos < documentContent.length() && linesConsumed < lineCount) {
            int nextNewline = documentContent.indexOf('\n', pos);
            if (nextNewline < 0) {
                pos = documentContent.length();
                linesConsumed++;
                break;
            }
            linesConsumed++;
            if (linesConsumed < lineCount) {
                pos = nextNewline + 1;
            } else {
                pos = trailingNewline ? nextNewline + 1 : nextNewline;
            }
        }

        return Math.min(pos, documentContent.length());
    }

    /**
     * Extracts a candidate match from document.
     */
    private MatchResult.SimilarMatch extractCandidate(String documentContent, int start, int end) {
        String text = documentContent.substring(start, end);
        int startLine = countLines(documentContent.substring(0, start)) + 1;
        int endLine = startLine + countLines(text);
        return new MatchResult.SimilarMatch(text, startLine, endLine, 1.0);
    }

    /**
     * Gets the character offset of a line start.
     */
    private int getLineOffset(String text, int lineIndex) {
        if (lineIndex == 0) return 0;

        int line = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                if (line == lineIndex) {
                    return i + 1;
                }
            }
        }
        return text.length();
    }

    /**
     * Gets the character offset of a line end.
     */
    private int getLineEndOffset(String text, int lineIndex) {
        int line = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (line == lineIndex) {
                    return i;
                }
                line++;
            }
        }
        return text.length();
    }

    /**
     * Counts newlines in text.
     */
    private int countLines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * Calculates similarity between two strings using LCS ratio.
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1.isEmpty() || s2.isEmpty()) {
            return s1.equals(s2) ? 1.0 : 0.0;
        }

        int lcsLength = longestCommonSubsequenceLength(s1, s2);
        int maxLength = Math.max(s1.length(), s2.length());
        return (double) lcsLength / maxLength;
    }

    /**
     * Computes length of longest common subsequence.
     */
    private int longestCommonSubsequenceLength(String s1, String s2) {
        // Optimize for large strings - use line-based comparison
        if (s1.length() > 1000 || s2.length() > 1000) {
            return longestCommonSubsequenceLengthByLines(s1, s2);
        }

        int m = s1.length();
        int n = s2.length();

        // Use space-optimized LCS
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
            java.util.Arrays.fill(curr, 0);
        }

        return prev[n];
    }

    /**
     * Line-based LCS for large strings.
     */
    private int longestCommonSubsequenceLengthByLines(String s1, String s2) {
        String[] lines1 = s1.split("\n", -1); //$NON-NLS-1$
        String[] lines2 = s2.split("\n", -1); //$NON-NLS-1$

        int m = lines1.length;
        int n = lines2.length;

        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (lines1[i - 1].equals(lines2[j - 1])) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
            java.util.Arrays.fill(curr, 0);
        }

        // Convert line count to approximate character count
        int matchedLines = prev[n];
        int avgLineLength = (s1.length() + s2.length()) / (m + n + 1);
        return matchedLines * avgLineLength;
    }
}
