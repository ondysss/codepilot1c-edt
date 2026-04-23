/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.skills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses explicit skill mentions from user input.
 *
 * <p>Supports {@code $skill-name} syntax (e.g. {@code $review}, {@code $refactor}).
 * Mentions are resolved case-insensitively against the skill catalog.
 * Unknown mentions are silently ignored.
 */
public final class SkillMentionParser {

    private static final Pattern MENTION_PATTERN = Pattern.compile("\\$([a-zA-Z][a-zA-Z0-9_-]*)"); //$NON-NLS-1$

    private SkillMentionParser() {
    }

    /**
     * Extracts skill names from {@code $mention} tokens in user input.
     *
     * @param userInput the raw user message text
     * @return list of mentioned skill names (lowercase, deduplicated, in order of appearance)
     */
    public static List<String> extractMentions(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Collections.emptyList();
        }
        Matcher matcher = MENTION_PATTERN.matcher(userInput);
        List<String> mentions = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            if (seen.add(name)) {
                mentions.add(name);
            }
        }
        return Collections.unmodifiableList(mentions);
    }

    /**
     * Strips {@code $mention} tokens from user input, returning clean text.
     * Use this to send the cleaned message to the model after extracting mentions.
     *
     * @param userInput the raw user message text
     * @return text with $mentions removed and extra whitespace collapsed
     */
    public static String stripMentions(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return userInput;
        }
        return MENTION_PATTERN.matcher(userInput).replaceAll("").strip() //$NON-NLS-1$
                .replaceAll("\\s{2,}", " "); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
