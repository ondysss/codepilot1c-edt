/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Applies edit blocks to file content using fuzzy matching.
 *
 * <p>This is the single source of truth for edit application, used both
 * for preview generation and actual file modification.</p>
 *
 * <p>Key features:
 * <ul>
 *   <li>Applies multiple edits in correct order (bottom-up to preserve offsets)</li>
 *   <li>Uses fuzzy matching to find edit locations</li>
 *   <li>Generates detailed diagnostics for failed matches</li>
 *   <li>Produces per-hunk results for granular review</li>
 * </ul>
 */
public class FileEditApplier {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(FileEditApplier.class);

    private final FuzzyMatcher matcher;
    private final SearchReplaceFormat parser;

    /**
     * Creates a new file edit applier with default matcher.
     */
    public FileEditApplier() {
        this(new FuzzyMatcher(), new SearchReplaceFormat());
    }

    /**
     * Creates a new file edit applier with custom matcher.
     *
     * @param matcher the fuzzy matcher to use
     * @param parser the search/replace parser to use
     */
    public FileEditApplier(FuzzyMatcher matcher, SearchReplaceFormat parser) {
        this.matcher = matcher;
        this.parser = parser;
    }

    /**
     * Applies edit blocks to content and returns result with hunks.
     *
     * @param beforeContent the original file content
     * @param blocks the edit blocks to apply
     * @return the apply result with hunks and diagnostics
     */
    public ApplyResult apply(String beforeContent, List<EditBlock> blocks) {
        if (beforeContent == null) {
            beforeContent = ""; //$NON-NLS-1$
        }

        if (blocks == null || blocks.isEmpty()) {
            return ApplyResult.noChanges(beforeContent);
        }

        LOG.debug("FileEditApplier: applying %d blocks to content of %d chars", //$NON-NLS-1$
                blocks.size(), beforeContent.length());

        // Match all blocks first
        List<MatchedEdit> matchedEdits = new ArrayList<>();
        List<Hunk> failedHunks = new ArrayList<>();

        for (EditBlock block : blocks) {
            MatchResult matchResult = matcher.findMatch(block.getSearchText(), beforeContent);

            if (matchResult.isSuccess()) {
                MatchLocation location = matchResult.getLocation().orElseThrow();
                if (matchResult.getStrategy() != MatchStrategy.EXACT) {
                    ReplacementShapeSafety.SafetyResult safety = ReplacementShapeSafety.evaluate(
                            location.getMatchedText(), block.getReplaceText());
                    if (!safety.isSafe()) {
                        Hunk failedHunk = new Hunk(
                                block.getBlockIndex(),
                                location.getStartLine(),
                                location.getEndLine(),
                                location.getMatchedText(),
                                block.getReplaceText(),
                                HunkStatus.FAILED,
                                safety.reason()
                        );
                        failedHunks.add(failedHunk);
                        LOG.warn("FileEditApplier: block %d failed safety check: %s", //$NON-NLS-1$
                                block.getBlockIndex(), safety.reason());
                        continue;
                    }
                }
                matchedEdits.add(new MatchedEdit(block, location, matchResult.getStrategy()));
            } else {
                // Create failed hunk with feedback
                Hunk failedHunk = new Hunk(
                        block.getBlockIndex(),
                        -1, -1, // No location
                        block.getSearchText(),
                        block.getReplaceText(),
                        HunkStatus.FAILED,
                        matchResult.generateFeedback()
                );
                failedHunks.add(failedHunk);
                LOG.warn("FileEditApplier: block %d failed to match: %s", //$NON-NLS-1$
                        block.getBlockIndex(), matchResult.getErrorMessage());
            }
        }

        // Check for overlapping edits
        matchedEdits.sort(Comparator.comparingInt(e -> e.location.getStartOffset()));
        for (int i = 1; i < matchedEdits.size(); i++) {
            MatchedEdit prev = matchedEdits.get(i - 1);
            MatchedEdit curr = matchedEdits.get(i);
            if (prev.location.getEndOffset() > curr.location.getStartOffset()) {
                String error = String.format(
                        "Блоки %d и %d перекрываются в позициях %d-%d и %d-%d", //$NON-NLS-1$
                        prev.block.getBlockIndex(), curr.block.getBlockIndex(),
                        prev.location.getStartOffset(), prev.location.getEndOffset(),
                        curr.location.getStartOffset(), curr.location.getEndOffset());
                return ApplyResult.error(beforeContent, error);
            }
        }

        // Apply edits in reverse order (bottom-up) to preserve offsets
        List<Hunk> appliedHunks = new ArrayList<>();
        String currentContent = beforeContent;

        // Sort by offset descending
        matchedEdits.sort(Comparator.comparingInt((MatchedEdit e) -> e.location.getStartOffset()).reversed());

        for (MatchedEdit edit : matchedEdits) {
            int start = edit.location.getStartOffset();
            int end = edit.location.getEndOffset();

            // Apply the replacement
            String before = currentContent.substring(0, start);
            String after = currentContent.substring(end);
            String replacement = edit.block.getReplaceText();
            if (edit.strategy != MatchStrategy.EXACT) {
                ReplacementShapeSafety.SafetyResult safety = ReplacementShapeSafety.evaluateResult(
                        buildSafetyWindow(before, replacement, after));
                if (!safety.isSafe()) {
                    Hunk failedHunk = new Hunk(
                            edit.block.getBlockIndex(),
                            edit.location.getStartLine(),
                            edit.location.getEndLine(),
                            edit.location.getMatchedText(),
                            replacement,
                            HunkStatus.FAILED,
                            safety.reason()
                    );
                    failedHunks.add(failedHunk);
                    LOG.warn("FileEditApplier: block %d failed result safety check: %s", //$NON-NLS-1$
                            edit.block.getBlockIndex(), safety.reason());
                    continue;
                }
            }
            currentContent = before + replacement + after;

            // Create successful hunk
            Hunk hunk = new Hunk(
                    edit.block.getBlockIndex(),
                    edit.location.getStartLine(),
                    edit.location.getEndLine(),
                    edit.location.getMatchedText(),
                    edit.block.getReplaceText(),
                    HunkStatus.APPLIED,
                    "Применено с использованием стратегии: " + edit.strategy.getDisplayName() //$NON-NLS-1$
            );
            appliedHunks.add(hunk);
        }

        // Sort hunks by block index for display
        appliedHunks.sort(Comparator.comparingInt(Hunk::blockIndex));
        failedHunks.sort(Comparator.comparingInt(Hunk::blockIndex));

        // Combine all hunks
        List<Hunk> allHunks = new ArrayList<>();
        allHunks.addAll(appliedHunks);
        allHunks.addAll(failedHunks);
        allHunks.sort(Comparator.comparingInt(Hunk::blockIndex));

        LOG.info("FileEditApplier: %d/%d blocks applied successfully", //$NON-NLS-1$
                appliedHunks.size(), blocks.size());

        return new ApplyResult(beforeContent, currentContent, allHunks, failedHunks.isEmpty());
    }

    private String buildSafetyWindow(String before, String replacement, String after) {
        int beforeStart = Math.max(0, before.length() - 500);
        int afterEnd = Math.min(after.length(), 500);
        return before.substring(beforeStart) + replacement + after.substring(0, afterEnd);
    }

    /**
     * Applies edit blocks from a raw LLM response.
     *
     * @param beforeContent the original file content
     * @param llmResponse the raw LLM response containing SEARCH/REPLACE blocks
     * @return the apply result
     */
    public ApplyResult applyFromResponse(String beforeContent, String llmResponse) {
        List<EditBlock> blocks = parser.parse(llmResponse);

        if (blocks.isEmpty()) {
            // Check if this is old-style old_text/new_text format
            return ApplyResult.noChanges(beforeContent);
        }

        // Validate blocks
        List<String> errors = parser.validate(blocks);
        if (!errors.isEmpty()) {
            return ApplyResult.error(beforeContent, String.join("; ", errors)); //$NON-NLS-1$
        }

        return apply(beforeContent, blocks);
    }

    /**
     * Previews edit application without modifying content.
     *
     * <p>Returns the same result as {@link #apply} but marks hunks
     * as PREVIEW instead of APPLIED.</p>
     *
     * @param beforeContent the original file content
     * @param blocks the edit blocks to preview
     * @return the preview result
     */
    public ApplyResult preview(String beforeContent, List<EditBlock> blocks) {
        ApplyResult result = apply(beforeContent, blocks);

        // Convert APPLIED to PREVIEW status
        List<Hunk> previewHunks = new ArrayList<>();
        for (Hunk hunk : result.hunks()) {
            if (hunk.status() == HunkStatus.APPLIED) {
                previewHunks.add(new Hunk(
                        hunk.blockIndex(), hunk.startLine(), hunk.endLine(),
                        hunk.beforeText(), hunk.afterText(),
                        HunkStatus.PREVIEW, hunk.message()
                ));
            } else {
                previewHunks.add(hunk);
            }
        }

        return new ApplyResult(result.beforeContent(), result.afterContent(),
                previewHunks, result.allSuccessful());
    }

    /**
     * Helper class for matched edit tracking.
     */
    private static class MatchedEdit {
        final EditBlock block;
        final MatchLocation location;
        final MatchStrategy strategy;

        MatchedEdit(EditBlock block, MatchLocation location, MatchStrategy strategy) {
            this.block = block;
            this.location = location;
            this.strategy = strategy;
        }
    }

    /**
     * Status of a hunk.
     */
    public enum HunkStatus {
        /** Successfully applied */
        APPLIED,
        /** Preview only, not yet applied */
        PREVIEW,
        /** Failed to match */
        FAILED,
        /** Accepted by user */
        ACCEPTED,
        /** Rejected by user */
        REJECTED
    }

    /**
     * Represents a single hunk (change region) within a file.
     *
     * @param blockIndex the edit block index this hunk came from
     * @param startLine start line in original file (1-based, -1 if not found)
     * @param endLine end line in original file (1-based, -1 if not found)
     * @param beforeText the original text
     * @param afterText the replacement text
     * @param status the hunk status
     * @param message diagnostic or status message
     */
    public record Hunk(
            int blockIndex,
            int startLine,
            int endLine,
            String beforeText,
            String afterText,
            HunkStatus status,
            String message
    ) {
        /**
         * Returns whether this hunk was successfully matched.
         *
         * @return true if matched
         */
        public boolean isMatched() {
            return startLine >= 0;
        }

        /**
         * Returns the number of lines changed.
         *
         * @return line delta (positive for additions, negative for deletions)
         */
        public int getLineDelta() {
            int beforeLines = beforeText != null ? beforeText.split("\n", -1).length : 0; //$NON-NLS-1$
            int afterLines = afterText != null ? afterText.split("\n", -1).length : 0; //$NON-NLS-1$
            return afterLines - beforeLines;
        }

        /**
         * Returns a summary of this hunk.
         *
         * @return human-readable summary
         */
        public String getSummary() {
            if (!isMatched()) {
                return String.format("Блок %d: не найден", blockIndex + 1); //$NON-NLS-1$
            }
            int delta = getLineDelta();
            if (delta > 0) {
                return String.format("Строки %d-%d (+%d)", startLine, endLine, delta); //$NON-NLS-1$
            } else if (delta < 0) {
                return String.format("Строки %d-%d (%d)", startLine, endLine, delta); //$NON-NLS-1$
            } else {
                return String.format("Строки %d-%d", startLine, endLine); //$NON-NLS-1$
            }
        }
    }

    /**
     * Result of applying edits to a file.
     *
     * @param beforeContent the original file content
     * @param afterContent the content after applying edits
     * @param hunks the list of hunks (matched and unmatched)
     * @param allSuccessful true if all edits were applied successfully
     */
    public record ApplyResult(
            String beforeContent,
            String afterContent,
            List<Hunk> hunks,
            boolean allSuccessful
    ) {
        /**
         * Creates a result indicating no changes.
         *
         * @param content the unchanged content
         * @return the result
         */
        public static ApplyResult noChanges(String content) {
            return new ApplyResult(content, content, Collections.emptyList(), true);
        }

        /**
         * Creates an error result.
         *
         * @param content the unchanged content
         * @param error the error message
         * @return the result
         */
        public static ApplyResult error(String content, String error) {
            Hunk errorHunk = new Hunk(0, -1, -1, "", "", HunkStatus.FAILED, error); //$NON-NLS-1$ //$NON-NLS-2$
            return new ApplyResult(content, content, List.of(errorHunk), false);
        }

        /**
         * Returns hunks that were successfully applied.
         *
         * @return list of applied hunks
         */
        public List<Hunk> getAppliedHunks() {
            return hunks.stream()
                    .filter(h -> h.status() == HunkStatus.APPLIED || h.status() == HunkStatus.PREVIEW)
                    .toList();
        }

        /**
         * Returns hunks that failed to match.
         *
         * @return list of failed hunks
         */
        public List<Hunk> getFailedHunks() {
            return hunks.stream()
                    .filter(h -> h.status() == HunkStatus.FAILED)
                    .toList();
        }

        /**
         * Returns whether there are any changes.
         *
         * @return true if content was modified
         */
        public boolean hasChanges() {
            return !beforeContent.equals(afterContent);
        }

        /**
         * Generates a summary of the result.
         *
         * @return human-readable summary
         */
        public String getSummary() {
            int applied = getAppliedHunks().size();
            int failed = getFailedHunks().size();
            int total = hunks.size();

            if (total == 0) {
                return "Нет изменений"; //$NON-NLS-1$
            } else if (allSuccessful) {
                return String.format("Применено изменений: %d", applied); //$NON-NLS-1$
            } else {
                return String.format("Применено: %d/%d, ошибок: %d", applied, total, failed); //$NON-NLS-1$
            }
        }

        /**
         * Generates detailed feedback for failed hunks.
         *
         * @return feedback string for LLM retry
         */
        public String getFailureFeedback() {
            List<Hunk> failed = getFailedHunks();
            if (failed.isEmpty()) {
                return ""; //$NON-NLS-1$
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Не удалось применить следующие изменения:\n\n"); //$NON-NLS-1$

            for (Hunk hunk : failed) {
                sb.append(String.format("=== Блок %d ===\n", hunk.blockIndex() + 1)); //$NON-NLS-1$
                sb.append(hunk.message()).append("\n\n"); //$NON-NLS-1$
            }

            return sb.toString();
        }
    }
}
