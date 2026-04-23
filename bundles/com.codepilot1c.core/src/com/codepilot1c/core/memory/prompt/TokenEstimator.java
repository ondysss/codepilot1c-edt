/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.prompt;

/**
 * Simple token estimation for memory budget management.
 *
 * <p>Uses a heuristic of ~4 characters per token for mixed Russian/English text.
 * This is intentionally conservative to avoid budget overruns.</p>
 *
 * <p>For Cyrillic-heavy 1C content, actual token counts may be higher
 * (Cyrillic characters use more tokens than Latin), so we use a ratio of 3.5
 * characters per token as a safer estimate.</p>
 */
public final class TokenEstimator {

    /** Characters per token ratio for mixed Russian/English text. */
    private static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {
    }

    /**
     * Estimates the token count of the given text.
     *
     * @param text the text to estimate, may be null
     * @return estimated token count, 0 for null or empty text
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Truncates text to fit within a token budget.
     *
     * @param text the text to truncate
     * @param maxTokens maximum allowed tokens
     * @return truncated text, or original if it fits
     */
    public static String truncateToFit(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return ""; //$NON-NLS-1$
        }

        int maxChars = (int) (maxTokens * CHARS_PER_TOKEN);
        if (text.length() <= maxChars) {
            return text;
        }

        // Truncate at a line boundary if possible
        String truncated = text.substring(0, maxChars);
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > maxChars / 2) {
            truncated = truncated.substring(0, lastNewline);
        }

        return truncated + "\n... (truncated)"; //$NON-NLS-1$
    }
}
